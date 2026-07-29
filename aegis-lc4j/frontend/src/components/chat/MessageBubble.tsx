"use client";

import { memo, useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import clsx from "clsx";
import { AlertTriangle, Check, CircleStop, Copy, ShieldOff, Zap } from "lucide-react";
import type { Message } from "@/hooks/useChatStream";
import { BrandMark } from "@/components/BrandMark";
import { CardCarousel } from "./CardCarousel";
import { AccountCards } from "./AccountCards";
import { TransactionList } from "./TransactionList";
import { CaseStatusCard } from "./CaseStatusCard";
import { ApprovalCard } from "./ApprovalCard";
import { CitationChips } from "./CitationChips";
import { LedgerReceipt } from "./LedgerReceipt";
import { SpendingStatement } from "./SpendingStatement";
import { ProfileCard } from "./ProfileCard";

/**
 * Raw backend `source` values are internal enums; the customer sees a sentence.
 * Anything unmapped falls back to no badge rather than leaking the token.
 */
const SOURCE_LABEL: Record<string, { icon: typeof Zap; text: string; tone: string }> = {
  cache: { icon: Zap, text: "Instant answer", tone: "text-good-ink" },
  blocked: { icon: ShieldOff, text: "Stopped by guardrails", tone: "text-warning-ink" },
  denied: { icon: ShieldOff, text: "Not permitted on your accounts", tone: "text-warning-ink" },
  unavailable: { icon: AlertTriangle, text: "Assistant unavailable", tone: "text-critical-ink" },
};

export const MessageBubble = memo(function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === "user";
  const [copied, setCopied] = useState(false);
  const reduceMotion = useReducedMotion();

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(message.text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1400);
    } catch {
      /* clipboard blocked — the copy button simply doesn't confirm */
    }
  };

  const source = message.meta && message.meta.source !== "llm" ? message.meta.source : null;
  const badge = source ? SOURCE_LABEL[source] : null;

  const hasWidgets = Boolean(
    message.accounts?.length ||
      message.transactions?.length ||
      message.cards?.length ||
      message.case ||
      message.approval ||
      message.ledger?.length ||
      message.statement ||
      message.profile,
  );

  if (isUser) {
    return (
      <motion.div
        layout="position"
        initial={reduceMotion ? false : { opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
        className="flex w-full justify-end"
        aria-live="off"
      >
        <div className="max-w-[85%] rounded-2xl rounded-br-md bg-accent-strong px-4 py-2.5 text-body text-on-accent shadow-[0_10px_30px_-18px_var(--glow-a)] sm:max-w-[72%]">
          <span className="whitespace-pre-wrap">{message.text}</span>
        </div>
      </motion.div>
    );
  }

  return (
    <motion.div
      layout="position"
      initial={reduceMotion ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
      className="group flex w-full gap-3"
      aria-live="off"
    >
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center self-start rounded-lg border border-hairline bg-surface-2">
        <BrandMark size={14} gradientId={`avatar-${message.id}`} />
      </span>

      {/* Everything the assistant produced this turn shares one column, so a
          reply with three widgets reads as one answer rather than four cards. */}
      <div className="flex min-w-0 flex-1 flex-col items-start gap-2">
        {message.text && (
          <div
            className={clsx(
              "prose-chat max-w-full text-body",
              message.streaming && message.text && "caret",
            )}
          >
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.text}</ReactMarkdown>
          </div>
        )}

        {badge && (
          <div className={clsx("flex items-center gap-1.5 text-micro font-medium", badge.tone)}>
            <badge.icon size={12} className="shrink-0" />
            {badge.text}
            {message.meta?.source === "cache" && message.meta.elapsedMs != null && (
              <span className="text-muted">· {message.meta.elapsedMs}ms</span>
            )}
          </div>
        )}

        {message.interrupted && (
          <div className="flex items-center gap-1.5 text-micro font-medium text-warning-ink">
            {message.interrupted === "stopped" ? (
              <>
                <CircleStop size={12} className="shrink-0" /> You stopped this response
              </>
            ) : (
              <>
                <AlertTriangle size={12} className="shrink-0" /> Response interrupted — it may be incomplete
              </>
            )}
          </div>
        )}

        {message.citations && message.citations.length > 0 && (
          <CitationChips citations={message.citations} />
        )}

        {hasWidgets && (
          <div className="flex w-full flex-col gap-2 border-l border-hairline pl-3">
            {message.accounts && message.accounts.length > 0 && (
              <AccountCards accounts={message.accounts} />
            )}
            {message.transactions && message.transactions.length > 0 && (
              <TransactionList transactions={message.transactions} />
            )}
            {message.cards && message.cards.length > 0 && <CardCarousel cards={message.cards} />}
            {message.case && <CaseStatusCard caseData={message.case} />}
            {message.approval && <ApprovalCard approval={message.approval} />}
            {message.ledger && message.ledger.length > 0 && (
              <LedgerReceipt entries={message.ledger} />
            )}
            {message.statement && <SpendingStatement statement={message.statement} />}
            {message.profile && <ProfileCard profile={message.profile} />}
          </div>
        )}

        {!message.streaming && message.text && (
          <button
            onClick={copy}
            aria-label="Copy message"
            className="flex items-center gap-1.5 rounded-lg py-0.5 text-micro text-muted opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100"
          >
            {copied ? <Check size={12} className="text-good" /> : <Copy size={12} />}
            {copied ? "Copied" : "Copy"}
          </button>
        )}
      </div>
    </motion.div>
  );
});
