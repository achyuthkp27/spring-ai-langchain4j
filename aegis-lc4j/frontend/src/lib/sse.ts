

export interface ChatMeta {
  conversationId: string;
  answer: string;
  source: "llm" | "cache" | "blocked" | "unavailable";
  elapsedMs: number;
  similarity?: number | null;
  matchedQuestion?: string | null;
}

export interface CardData {
  cardId: string;
  accountId: string;
  type: "DEBIT" | "CREDIT";
  network: "VISA" | "MASTERCARD";
  last4: string;
  status: "ACTIVE" | "FROZEN";
}

export interface AccountData {
  accountId: string;
  tenantId: string;
  ownerUserId: string;
  balance: number;
  type: "CHECKING" | "SAVINGS";
}

export interface TransactionData {
  txnId: string;
  accountId: string;
  date: string;
  amount: number;
  merchant: string;
  direction: "DEBIT" | "CREDIT";
}

export interface CaseData {
  caseId: string;
  accountId: string;
  transactionId: string;
  reason: string;
  status: string;
}

export interface ApprovalData {
  approvalId: string;
  subject: string;
  amount: number;
  requestedBy: string;
  status: string;
}

export interface CitationData {
  source: string;
  snippet: string;
}

export interface LedgerEntryData {
  entryId: string;
  accountId: string;
  amount: number;
  direction: "DEBIT" | "CREDIT";
  balanceAfter: number;
  reference: string;
  postedAt: string;
}

export interface ProfileData {
  tenantId: string;
  userId: string;
  email: string;
  phone: string;
  lowBalanceAlerts: boolean;
  largeTransactionAlerts: boolean;
  travelNoticeUntil: string | null;
  travelDestination: string | null;
}

export interface StatementData {
  accountId: string;
  byCategory: Record<string, number>;
  totalDebits: number;
  totalCredits: number;
}

/**
 * Why the stream ended unsuccessfully.
 * - `http`   — the request itself failed (status carries 401/403/5xx).
 * - `network`— fetch threw (connection dropped before/after headers).
 * - `stream` — the body closed without a terminating `meta` event, so the
 *   answer is incomplete. This is what stops a bubble stranding in `streaming`.
 */
export interface StreamError {
  kind: "http" | "network" | "stream";
  status?: number;
}

export interface StreamHandlers {
  onToken: (text: string) => void;
  onStatus: (status: string) => void;
  onCards: (cards: CardData[]) => void;
  onAccounts: (accounts: AccountData[]) => void;
  onTransactions: (txns: TransactionData[]) => void;
  onCase: (c: CaseData) => void;
  onApproval: (a: ApprovalData) => void;
  onCitations: (citations: CitationData[]) => void;
  onLedger: (entries: LedgerEntryData[]) => void;
  onProfile: (profile: ProfileData) => void;
  onStatement: (statement: StatementData) => void;
  onMeta: (meta: ChatMeta) => void;
  onError: (err: StreamError) => void;
  onAbort?: () => void;
}

/**
 * Drives one assistant turn. Guarantees exactly one terminal callback —
 * `onMeta` (success), `onAbort` (cancelled), or `onError` (anything else) — so
 * the caller can always leave the "streaming" state. `reauth`, when supplied,
 * is called once on a 401 to obtain a fresh token and the request is retried.
 */
export async function streamChat(
  token: string,
  conversationId: string,
  message: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
  reauth?: () => Promise<string | null>,
): Promise<void> {
  let res: Response;
  let bearer = token;
  try {
    for (let attempt = 0; ; attempt++) {
      res = await fetch("/api/assistant", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${bearer}`,
          Accept: "text/event-stream",
        },
        body: JSON.stringify({ conversationId, message }),
        signal,
      });
      if (res.status === 401 && reauth && attempt === 0) {
        const fresh = await reauth();
        if (fresh) {
          bearer = fresh;
          continue;
        }
      }
      break;
    }
  } catch (e) {
    if ((e as DOMException)?.name === "AbortError") handlers.onAbort?.();
    else handlers.onError({ kind: "network" });
    return;
  }
  if (!res.ok || !res.body) {
    handlers.onError({ kind: "http", status: res.status });
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let terminated = false; // a `meta` (or explicit `error`) frame closed the turn

  const dispatch = (event: string, data: string) => {
    let payload: unknown;
    try {
      payload = JSON.parse(data);
    } catch {
      return; // malformed frame — skip, the terminal guard still applies
    }
    const p = payload as Record<string, unknown>;
    switch (event) {
      case "token":
        if (typeof p.t === "string") handlers.onToken(p.t);
        break;
      case "status":
        if (typeof p.s === "string") handlers.onStatus(p.s);
        break;
      case "cards":
        if (Array.isArray(payload)) handlers.onCards(payload as CardData[]);
        break;
      case "accounts":
        if (Array.isArray(payload)) handlers.onAccounts(payload as AccountData[]);
        break;
      case "transactions":
        if (Array.isArray(payload)) handlers.onTransactions(payload as TransactionData[]);
        break;
      case "case":
        handlers.onCase(payload as CaseData);
        break;
      case "approval":
        handlers.onApproval(payload as ApprovalData);
        break;
      case "citations":
        if (Array.isArray(payload)) handlers.onCitations(payload as CitationData[]);
        break;
      case "ledger":
        if (Array.isArray(payload)) handlers.onLedger(payload as LedgerEntryData[]);
        break;
      case "profile":
        handlers.onProfile(payload as ProfileData);
        break;
      case "statement":
        handlers.onStatement(payload as StatementData);
        break;
      case "meta":
        terminated = true;
        handlers.onMeta(payload as ChatMeta);
        break;
      case "error":
        terminated = true;
        handlers.onError({ kind: "stream" });
        break;
      default:
        break; // unknown event name — ignore, don't let it strand the turn
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) >= 0) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        let event = "message";
        const dataLines: string[] = [];
        for (const line of frame.split("\n")) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          else if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
        }
        if (dataLines.length) dispatch(event, dataLines.join("\n"));
      }
    }
  } catch (e) {
    if ((e as DOMException)?.name === "AbortError") handlers.onAbort?.();
    else handlers.onError({ kind: "network" });
    return;
  }

  // The body closed cleanly but no `meta` arrived: the answer is incomplete.
  if (!terminated) handlers.onError({ kind: "stream" });
}
