"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ensureSession, reauth, fetchHistory, type HistoryMessage } from "@/lib/api";
import {
  streamChat,
  type AccountData,
  type ApprovalData,
  type CardData,
  type CaseData,
  type ChatMeta,
  type CitationData,
  type LedgerEntryData,
  type ProfileData,
  type StatementData,
  type StreamError,
  type TransactionData,
} from "@/lib/sse";
import {
  AccountArraySchema,
  ApprovalSchema,
  CardArraySchema,
  CaseSchema,
  CitationArraySchema,
  LedgerArraySchema,
  matches,
  ProfileSchema,
  StatementSchema,
  TransactionArraySchema,
} from "@/lib/schemas";

export interface Message {
  id: string;
  role: "user" | "assistant";
  text: string;
  streaming?: boolean;
  /** How a non-normal turn ended, for a trailing marker in the bubble. */
  interrupted?: "stopped" | "error";
  meta?: ChatMeta;
  cards?: CardData[];
  accounts?: AccountData[];
  transactions?: TransactionData[];
  case?: CaseData;
  approval?: ApprovalData;
  citations?: CitationData[];
  ledger?: LedgerEntryData[];
  profile?: ProfileData;
  statement?: StatementData;
}

export interface ChatState {
  messages: Message[];
  statuses: string[]; 
  busy: boolean;
  historyError: boolean;
}

let nextId = 0;
const id = () => `m${++nextId}`;

export function mergeById<T, K>(existing: T[] | undefined, incoming: T[], keyOf: (t: T) => K): T[] {
  const byId = new Map((existing ?? []).map((t) => [keyOf(t), t]));
  incoming.forEach((t) => byId.set(keyOf(t), t));
  return [...byId.values()];
}
const mergeCards = (existing: CardData[] | undefined, incoming: CardData[]) =>
  mergeById(existing, incoming, (c) => c.cardId);
const mergeAccounts = (existing: AccountData[] | undefined, incoming: AccountData[]) =>
  mergeById(existing, incoming, (a) => a.accountId);
const mergeTransactions = (existing: TransactionData[] | undefined, incoming: TransactionData[]) =>
  mergeById(existing, incoming, (t) => t.txnId);
const mergeLedger = (existing: LedgerEntryData[] | undefined, incoming: LedgerEntryData[]) =>
  mergeById(existing, incoming, (e) => e.entryId);

// Rehydrating history is the same trust boundary as the live stream, so the
// persisted widget payloads get the same schema gate — a drifted or corrupt row
// is skipped rather than rendered as garbage.
function hydrateAssistantMessage(h: HistoryMessage): Message {
  let msg: Message = { id: id(), role: "assistant", text: h.text };
  for (const w of h.widgets) {
    const p = w.payload;
    switch (w.type) {
      case "cards":
        if (matches(CardArraySchema, p)) msg = { ...msg, cards: mergeCards(msg.cards, p as CardData[]) };
        break;
      case "accounts":
        if (matches(AccountArraySchema, p)) msg = { ...msg, accounts: mergeAccounts(msg.accounts, p as AccountData[]) };
        break;
      case "transactions":
        if (matches(TransactionArraySchema, p))
          msg = { ...msg, transactions: mergeTransactions(msg.transactions, p as TransactionData[]) };
        break;
      case "case":
        if (matches(CaseSchema, p)) msg = { ...msg, case: p as CaseData };
        break;
      case "approval":
        if (matches(ApprovalSchema, p)) msg = { ...msg, approval: p as ApprovalData };
        break;
      case "citations":
        if (matches(CitationArraySchema, p)) msg = { ...msg, citations: p as CitationData[] };
        break;
      case "ledger":
        if (matches(LedgerArraySchema, p)) msg = { ...msg, ledger: mergeLedger(msg.ledger, p as LedgerEntryData[]) };
        break;
      case "profile":
        if (matches(ProfileSchema, p)) msg = { ...msg, profile: p as ProfileData };
        break;
      case "statement":
        if (matches(StatementSchema, p)) msg = { ...msg, statement: p as StatementData };
        break;
    }
  }
  return msg;
}

function errorCopy(err: StreamError): string {
  if (err.kind === "stream") return "The response was cut short. Please try again.";
  if (err.status === 401) return "Your session expired. Please refresh and sign in again.";
  if (err.status === 403) return "I can't help with that on your account.";
  if (err.status === 429) return "You're sending requests too quickly. Please wait a moment and retry.";
  if (err.status && err.status >= 500) return "The assistant is temporarily unavailable. Please try again.";
  return "Something went wrong reaching the assistant. Please try again.";
}

export function useChatStream(conversationId: string) {
  const [state, setState] = useState<ChatState>({
    messages: [],
    statuses: [],
    busy: false,
    historyError: false,
  });
  const abortRef = useRef<AbortController | null>(null);

  // The conversation currently on screen. Every async callback checks this
  // before writing state, so a stream or history fetch that resolves after the
  // user has switched away can never touch the conversation now displayed.
  const activeIdRef = useRef(conversationId);
  activeIdRef.current = conversationId;

  // Monotonic history-request id: only the newest fetch is allowed to apply,
  // so a slow response can't overwrite a conversation loaded after it.
  const historyReqRef = useRef(0);

  // Abort any in-flight stream when the conversation changes or the hook unmounts.
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, [conversationId]);

  const loadHistory = useCallback(async () => {
    const seq = ++historyReqRef.current;
    const reqConvId = conversationId;
    try {
      const history = await fetchHistory(conversationId);
      if (seq !== historyReqRef.current || activeIdRef.current !== reqConvId) return;
      setState({
        messages: history.map((h) =>
          h.role === "assistant" ? hydrateAssistantMessage(h) : { id: id(), role: "user", text: h.text },
        ),
        statuses: [],
        busy: false,
        historyError: false,
      });
    } catch {
      if (seq !== historyReqRef.current || activeIdRef.current !== reqConvId) return;
      setState((s) => ({ ...s, historyError: true }));
    }
  }, [conversationId]);

  const send = useCallback(
    async (text: string) => {
      const sendConvId = conversationId;
      const isCurrent = () => activeIdRef.current === sendConvId;

      const userMsg: Message = { id: id(), role: "user", text };
      const botId = id();
      setState((s) => ({
        ...s,
        messages: [...s.messages, userMsg, { id: botId, role: "assistant", text: "", streaming: true }],
        statuses: [],
        busy: true,
      }));

      // A previous stream on this hook must be cancelled before starting a new one.
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      const patchBot = (fn: (m: Message) => Message) => {
        if (!isCurrent()) return;
        setState((s) => ({ ...s, messages: s.messages.map((m) => (m.id === botId ? fn(m) : m)) }));
      };
      const settle = () => {
        if (isCurrent()) setState((s) => ({ ...s, statuses: [], busy: false }));
      };

      // Ensure a session cookie exists before streaming (the proxy needs it).
      try {
        await ensureSession();
      } catch {
        patchBot((m) => ({
          ...m,
          streaming: false,
          interrupted: "error",
          text: "Couldn't sign you in. Please refresh and try again.",
        }));
        settle();
        return;
      }

      await streamChat(
        sendConvId,
        text,
        {
          onToken: (t) => patchBot((m) => ({ ...m, text: m.text + t })),
          onStatus: (status) => {
            if (isCurrent()) setState((s) => ({ ...s, statuses: [...s.statuses.slice(-3), status] }));
          },
          onCards: (cards) => patchBot((m) => ({ ...m, cards: mergeCards(m.cards, cards) })),
          onAccounts: (accounts) => patchBot((m) => ({ ...m, accounts: mergeAccounts(m.accounts, accounts) })),
          onTransactions: (txns) =>
            patchBot((m) => ({ ...m, transactions: mergeTransactions(m.transactions, txns) })),
          onCase: (c) => patchBot((m) => ({ ...m, case: c })),
          onApproval: (a) => patchBot((m) => ({ ...m, approval: a })),
          onCitations: (citations) => patchBot((m) => ({ ...m, citations })),
          onLedger: (entries) => patchBot((m) => ({ ...m, ledger: mergeLedger(m.ledger, entries) })),
          onProfile: (profile) => patchBot((m) => ({ ...m, profile })),
          onStatement: (statement) => patchBot((m) => ({ ...m, statement })),
          onMeta: (meta) => {
            patchBot((m) => ({ ...m, streaming: false, meta }));
            settle();
          },
          onError: (err) => {
            patchBot((m) => ({
              ...m,
              streaming: false,
              // Partial text is kept but flagged, so a cut-off answer never
              // reads as a confident, complete one.
              interrupted: "error",
              text: m.text || errorCopy(err),
            }));
            settle();
          },
          onAbort: () => {
            patchBot((m) => ({ ...m, streaming: false, interrupted: m.text ? "stopped" : undefined }));
            settle();
          },
        },
        controller.signal,
        reauth,
      );
    },
    [conversationId],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  return { ...state, send, stop, loadHistory };
}
