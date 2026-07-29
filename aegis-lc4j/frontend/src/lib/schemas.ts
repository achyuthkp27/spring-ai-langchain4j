/**
 * Runtime shapes for everything crossing the network boundary — SSE widget
 * frames and rehydrated history payloads. The backend is the only producer, so
 * these are insurance, not defence: a payload that stops matching (contract
 * drift, a truncated frame) makes the widget quietly not render rather than
 * crashing the render or drawing garbage. High-frequency token/status frames
 * are left to cheap typeof checks in sse.ts; only structured payloads are
 * validated here.
 *
 * A few dozen lines of predicates rather than a schema library — validating a
 * handful of flat shapes doesn't justify shipping one to the client.
 */

export type Validator = (value: unknown) => boolean;

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

const str: Validator = (v) => typeof v === "string";
const num: Validator = (v) => typeof v === "number" && Number.isFinite(v);
const bool: Validator = (v) => typeof v === "boolean";
const strOrNull: Validator = (v) => v === null || typeof v === "string";
const numOrNull: Validator = (v) => v === null || num(v);

const oneOf =
  (...opts: string[]): Validator =>
  (v) =>
    typeof v === "string" && opts.includes(v);

const optional =
  (fn: Validator): Validator =>
  (v) =>
    v === undefined || fn(v);

const recordOfNumber: Validator = (v) => isObject(v) && Object.values(v).every(num);

const shape =
  (fields: Record<string, Validator>): Validator =>
  (v) =>
    isObject(v) && Object.entries(fields).every(([k, fn]) => fn(v[k]));

const arrayOf =
  (fn: Validator): Validator =>
  (v) =>
    Array.isArray(v) && v.every(fn);

export const CardSchema = shape({
  cardId: str,
  accountId: str,
  type: oneOf("DEBIT", "CREDIT"),
  network: oneOf("VISA", "MASTERCARD"),
  last4: str,
  status: oneOf("ACTIVE", "FROZEN"),
});

export const AccountSchema = shape({
  accountId: str,
  tenantId: str,
  ownerUserId: str,
  balance: num,
  type: oneOf("CHECKING", "SAVINGS"),
});

export const TransactionSchema = shape({
  txnId: str,
  accountId: str,
  date: str,
  amount: num,
  merchant: str,
  direction: oneOf("DEBIT", "CREDIT"),
});

export const CaseSchema = shape({
  caseId: str,
  accountId: str,
  transactionId: str,
  reason: str,
  status: str,
});

export const ApprovalSchema = shape({
  approvalId: str,
  subject: str,
  amount: num,
  requestedBy: str,
  status: str,
});

export const CitationSchema = shape({ source: str, snippet: str });

export const LedgerEntrySchema = shape({
  entryId: str,
  accountId: str,
  amount: num,
  direction: oneOf("DEBIT", "CREDIT"),
  balanceAfter: num,
  reference: str,
  postedAt: str,
});

export const ProfileSchema = shape({
  tenantId: str,
  userId: str,
  email: str,
  phone: str,
  lowBalanceAlerts: bool,
  largeTransactionAlerts: bool,
  travelNoticeUntil: strOrNull,
  travelDestination: strOrNull,
});

export const StatementSchema = shape({
  accountId: str,
  byCategory: recordOfNumber,
  totalDebits: num,
  totalCredits: num,
});

export const MetaSchema = shape({
  conversationId: str,
  answer: str,
  source: oneOf("llm", "cache", "blocked", "unavailable"),
  elapsedMs: num,
  similarity: optional(numOrNull),
  matchedQuestion: optional(strOrNull),
});

export const CardArraySchema = arrayOf(CardSchema);
export const AccountArraySchema = arrayOf(AccountSchema);
export const TransactionArraySchema = arrayOf(TransactionSchema);
export const CitationArraySchema = arrayOf(CitationSchema);
export const LedgerArraySchema = arrayOf(LedgerEntrySchema);

/** True if `value` matches `schema`. Never mutates the value — a pass-through gate. */
export function matches(schema: Validator, value: unknown): boolean {
  return schema(value);
}
