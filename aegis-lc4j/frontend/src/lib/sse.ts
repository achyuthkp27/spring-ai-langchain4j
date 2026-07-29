

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
  onError: (err: Error) => void;
  onAbort?: () => void;
}

export async function streamChat(
  token: string,
  conversationId: string,
  message: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch("/api/assistant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream",
      },
      body: JSON.stringify({ conversationId, message }),
      signal,
    });
  } catch (e) {
    handlers.onError(e instanceof Error ? e : new Error(String(e)));
    return;
  }
  if (!res.ok || !res.body) {
    handlers.onError(new Error(`assistant request failed: ${res.status}`));
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const dispatch = (event: string, data: string) => {
    try {
      const payload = JSON.parse(data);
      if (event === "token" && typeof payload.t === "string") handlers.onToken(payload.t);
      else if (event === "status" && typeof payload.s === "string") handlers.onStatus(payload.s);
      else if (event === "cards" && Array.isArray(payload)) handlers.onCards(payload as CardData[]);
      else if (event === "accounts" && Array.isArray(payload)) handlers.onAccounts(payload as AccountData[]);
      else if (event === "transactions" && Array.isArray(payload)) handlers.onTransactions(payload as TransactionData[]);
      else if (event === "case") handlers.onCase(payload as CaseData);
      else if (event === "approval") handlers.onApproval(payload as ApprovalData);
      else if (event === "citations" && Array.isArray(payload)) handlers.onCitations(payload as CitationData[]);
      else if (event === "ledger" && Array.isArray(payload)) handlers.onLedger(payload as LedgerEntryData[]);
      else if (event === "profile") handlers.onProfile(payload as ProfileData);
      else if (event === "statement") handlers.onStatement(payload as StatementData);
      else if (event === "meta") handlers.onMeta(payload as ChatMeta);
    } catch {
      
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
    if ((e as DOMException)?.name === "AbortError") {
      handlers.onAbort?.();
    } else {
      handlers.onError(e instanceof Error ? e : new Error(String(e)));
    }
  }
}
