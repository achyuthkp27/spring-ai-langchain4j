"use client";

import { memo, useState } from "react";
import { motion } from "framer-motion";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import clsx from "clsx";
import { Check, Copy, Landmark, Shield, Zap } from "lucide-react";
import type { Message } from "@/hooks/useChatStream";
import { CardCarousel } from "./CardCarousel";
import { AccountCards } from "./AccountCards";
import { TransactionList } from "./TransactionList";
import { CaseStatusCard } from "./CaseStatusCard";
import { ApprovalCard } from "./ApprovalCard";
import { CitationChips } from "./CitationChips";
import { LedgerReceipt } from "./LedgerReceipt";
import { SpendingStatement } from "./SpendingStatement";
import { ProfileCard } from "./ProfileCard";

export const MessageBubble = memo(function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === "user";
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(message.text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1400);
    } catch {
      
    }
  };

  return (
    <motion.div
      layout="position"
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ type: "spring", stiffness: 380, damping: 30 }}
      className={clsx("group flex w-full items-end gap-2", isUser ? "justify-end" : "justify-start")}
      aria-live="off"
    >
      {!isUser && (
        <div className="grid h-7 w-7 shrink-0 place-items-center self-start rounded-md bg-foreground text-background">
          <Landmark size={13} />
        </div>
      )}

      <div className={clsx("flex max-w-[85%] flex-col sm:max-w-[70%]", isUser && "items-end")}>
        <div
          className={clsx(
            "rounded-2xl px-4 py-2.5 text-[15px] leading-relaxed",
            isUser
              ? "bg-accent text-white rounded-br-md"
              : "bg-surface border border-border-soft rounded-bl-md",
          )}
        >
          {isUser ? (
            <span className="whitespace-pre-wrap">{message.text}</span>
          ) : (
            <div className={clsx("prose-chat", message.streaming && message.text && "caret")}>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.text}</ReactMarkdown>
            </div>
          )}
          {message.meta && message.meta.source !== "llm" && (

            <div className="mt-1.5 flex items-center gap-1 text-[11px] font-medium text-muted">
              {message.meta.source === "cache" ? (
                <>
                  <Zap size={11} className="text-good" /> instant answer · {message.meta.elapsedMs}ms
                </>
              ) : (
                <>
                  <Shield size={11} className="text-warning" /> {message.meta.source}
                </>
              )}
            </div>
          )}
          {message.citations && message.citations.length > 0 && (
            <CitationChips citations={message.citations} />
          )}
        </div>

        {message.accounts && message.accounts.length > 0 && <AccountCards accounts={message.accounts} />}
        {message.transactions && message.transactions.length > 0 && (
          <TransactionList transactions={message.transactions} />
        )}
        {message.cards && message.cards.length > 0 && <CardCarousel cards={message.cards} />}
        {message.case && <CaseStatusCard caseData={message.case} />}
        {message.approval && <ApprovalCard approval={message.approval} />}
        {message.ledger && message.ledger.length > 0 && <LedgerReceipt entries={message.ledger} />}
        {message.statement && <SpendingStatement statement={message.statement} />}
        {message.profile && <ProfileCard profile={message.profile} />}

        {!message.streaming && message.text && (
          <button
            onClick={copy}
            aria-label="Copy message"
            className="mt-1 flex items-center gap-1 self-start px-1 text-[11px] text-muted opacity-0 transition-opacity hover:text-foreground group-hover:opacity-100"
          >
            {copied ? <Check size={11} className="text-good" /> : <Copy size={11} />}
            {copied ? "Copied" : "Copy"}
          </button>
        )}
      </div>
    </motion.div>
  );
});
