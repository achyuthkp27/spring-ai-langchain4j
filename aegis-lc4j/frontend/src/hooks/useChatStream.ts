"use client";

import { useCallback, useRef, useState } from "react";
import { getToken, fetchHistory, type HistoryMessage } from "@/lib/api";
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
  type TransactionData,
} from "@/lib/sse";

export interface Message {
  id: string;
  role: "user" | "assistant";
  text: string;
  streaming?: boolean;
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

function mergeById<T, K>(existing: T[] | undefined, incoming: T[], keyOf: (t: T) => K): T[] {
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

function hydrateAssistantMessage(h: HistoryMessage): Message {
  let msg: Message = { id: id(), role: "assistant", text: h.text };
  for (const w of h.widgets) {
    switch (w.type) {
      case "cards":
        msg = { ...msg, cards: mergeCards(msg.cards, w.payload as CardData[]) };
        break;
      case "accounts":
        msg = { ...msg, accounts: mergeAccounts(msg.accounts, w.payload as AccountData[]) };
        break;
      case "transactions":
        msg = { ...msg, transactions: mergeTransactions(msg.transactions, w.payload as TransactionData[]) };
        break;
      case "case":
        msg = { ...msg, case: w.payload as CaseData };
        break;
      case "approval":
        msg = { ...msg, approval: w.payload as ApprovalData };
        break;
      case "citations":
        msg = { ...msg, citations: w.payload as CitationData[] };
        break;
      case "ledger":
        msg = { ...msg, ledger: mergeLedger(msg.ledger, w.payload as LedgerEntryData[]) };
        break;
      case "profile":
        msg = { ...msg, profile: w.payload as ProfileData };
        break;
      case "statement":
        msg = { ...msg, statement: w.payload as StatementData };
        break;
    }
  }
  return msg;
}

export function useChatStream(conversationId: string) {
  const [state, setState] = useState<ChatState>({
    messages: [],
    statuses: [],
    busy: false,
    historyError: false,
  });
  const abortRef = useRef<AbortController | null>(null);

  const loadHistory = useCallback(async () => {
    try {
      const history = await fetchHistory(conversationId);
      setState({
        messages: history.map((h) =>
          h.role === "assistant" ? hydrateAssistantMessage(h) : { id: id(), role: "user", text: h.text },
        ),
        statuses: [],
        busy: false,
        historyError: false,
      });
    } catch {
      setState((s) => ({ ...s, historyError: true }));
    }
  }, [conversationId]);

  const send = useCallback(
    async (text: string) => {
      const userMsg: Message = { id: id(), role: "user", text };
      const botId = id();
      setState((s) => ({
        ...s,
        messages: [...s.messages, userMsg, { id: botId, role: "assistant", text: "", streaming: true }],
        statuses: [],
        busy: true,
      }));

      const controller = new AbortController();
      abortRef.current = controller;

      const patchBot = (fn: (m: Message) => Message) =>
        setState((s) => ({
          ...s,
          messages: s.messages.map((m) => (m.id === botId ? fn(m) : m)),
        }));

      let token: string;
      try {
        token = await getToken();
      } catch {
        patchBot((m) => ({
          ...m,
          streaming: false,
          text: "Couldn't sign you in. Please refresh and try again.",
        }));
        setState((s) => ({ ...s, statuses: [], busy: false }));
        return;
      }

      await streamChat(
        token,
        conversationId,
        text,
        {
          onToken: (t) => patchBot((m) => ({ ...m, text: m.text + t })),
          onStatus: (status) =>
            setState((s) => ({ ...s, statuses: [...s.statuses.slice(-3), status] })),
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
            setState((s) => ({ ...s, statuses: [], busy: false }));
          },
          onError: () => {
            patchBot((m) => ({
              ...m,
              streaming: false,
              text: m.text || "Something went wrong reaching the assistant. Please try again.",
            }));
            setState((s) => ({ ...s, statuses: [], busy: false }));
          },
          onAbort: () => {
            patchBot((m) => ({ ...m, streaming: false }));
            setState((s) => ({ ...s, statuses: [], busy: false }));
          },
        },
        controller.signal,
      );
    },
    [conversationId],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  return { ...state, send, stop, loadHistory };
}
