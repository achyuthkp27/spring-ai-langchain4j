import { z } from "zod";

/**
 * Runtime shapes for everything crossing the network boundary — SSE widget
 * frames and rehydrated history payloads. The backend is the only producer, so
 * these are insurance, not defence: a schema that stops matching (a backend
 * contract drift, a truncated frame) makes the widget quietly not render rather
 * than crashing the render or drawing garbage. High-frequency token/status
 * frames are left to cheap typeof checks in sse.ts; only structured payloads
 * are parsed here.
 *
 * Enumerated fields the components switch on (`type`, `network`, `direction`,
 * card `status`, meta `source`) are strict; open-ended status strings that the
 * components map with `.includes(...)` stay `z.string()`.
 */

export const CardSchema = z.object({
  cardId: z.string(),
  accountId: z.string(),
  type: z.enum(["DEBIT", "CREDIT"]),
  network: z.enum(["VISA", "MASTERCARD"]),
  last4: z.string(),
  status: z.enum(["ACTIVE", "FROZEN"]),
});

export const AccountSchema = z.object({
  accountId: z.string(),
  tenantId: z.string(),
  ownerUserId: z.string(),
  balance: z.number(),
  type: z.enum(["CHECKING", "SAVINGS"]),
});

export const TransactionSchema = z.object({
  txnId: z.string(),
  accountId: z.string(),
  date: z.string(),
  amount: z.number(),
  merchant: z.string(),
  direction: z.enum(["DEBIT", "CREDIT"]),
});

export const CaseSchema = z.object({
  caseId: z.string(),
  accountId: z.string(),
  transactionId: z.string(),
  reason: z.string(),
  status: z.string(),
});

export const ApprovalSchema = z.object({
  approvalId: z.string(),
  subject: z.string(),
  amount: z.number(),
  requestedBy: z.string(),
  status: z.string(),
});

export const CitationSchema = z.object({
  source: z.string(),
  snippet: z.string(),
});

export const LedgerEntrySchema = z.object({
  entryId: z.string(),
  accountId: z.string(),
  amount: z.number(),
  direction: z.enum(["DEBIT", "CREDIT"]),
  balanceAfter: z.number(),
  reference: z.string(),
  postedAt: z.string(),
});

export const ProfileSchema = z.object({
  tenantId: z.string(),
  userId: z.string(),
  email: z.string(),
  phone: z.string(),
  lowBalanceAlerts: z.boolean(),
  largeTransactionAlerts: z.boolean(),
  travelNoticeUntil: z.string().nullable(),
  travelDestination: z.string().nullable(),
});

export const StatementSchema = z.object({
  accountId: z.string(),
  byCategory: z.record(z.string(), z.number()),
  totalDebits: z.number(),
  totalCredits: z.number(),
});

export const MetaSchema = z.object({
  conversationId: z.string(),
  answer: z.string(),
  source: z.enum(["llm", "cache", "blocked", "unavailable"]),
  elapsedMs: z.number(),
  similarity: z.number().nullable().optional(),
  matchedQuestion: z.string().nullable().optional(),
});

export const CardArraySchema = z.array(CardSchema);
export const AccountArraySchema = z.array(AccountSchema);
export const TransactionArraySchema = z.array(TransactionSchema);
export const CitationArraySchema = z.array(CitationSchema);
export const LedgerArraySchema = z.array(LedgerEntrySchema);

/** True if `value` matches `schema`; used as a pass-through gate that never mutates the value. */
export function matches<T>(schema: z.ZodType<T>, value: unknown): boolean {
  return schema.safeParse(value).success;
}
