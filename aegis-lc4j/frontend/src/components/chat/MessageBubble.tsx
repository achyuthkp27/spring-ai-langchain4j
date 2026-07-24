"use client";

import { motion } from "framer-motion";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import clsx from "clsx";
import { Shield, Zap } from "lucide-react";
import type { Message } from "@/hooks/useChatStream";

export function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === "user";
  return (
    <motion.div
      layout="position"
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ type: "spring", stiffness: 380, damping: 30 }}
      className={clsx("flex w-full", isUser ? "justify-end" : "justify-start")}
    >
      <div
        className={clsx(
          "max-w-[85%] sm:max-w-[70%] rounded-2xl px-4 py-2.5 text-[15px] leading-relaxed shadow-sm",
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
          <div className="mt-1.5 flex items-center gap-1 text-[11px] text-muted">
            {message.meta.source === "cache" ? (
              <>
                <Zap size={11} /> instant answer · {message.meta.elapsedMs}ms
              </>
            ) : (
              <>
                <Shield size={11} /> {message.meta.source}
              </>
            )}
          </div>
        )}
      </div>
    </motion.div>
  );
}
