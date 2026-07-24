"use client";

import { useRef, useState } from "react";
import { motion } from "framer-motion";
import { SendHorizonal } from "lucide-react";

export function Composer({
  onSend,
  disabled,
}: {
  onSend: (text: string) => void;
  disabled: boolean;
}) {
  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    const t = text.trim();
    if (!t || disabled) return;
    onSend(t);
    setText("");
    if (ref.current) ref.current.style.height = "auto";
  };

  return (
    <div className="flex items-end gap-2 rounded-2xl border border-border-soft bg-surface p-2 shadow-sm focus-within:ring-2 focus-within:ring-accent/40 transition-shadow">
      <textarea
        ref={ref}
        value={text}
        rows={1}
        placeholder="Ask about your accounts, cards, or a charge…"
        className="flex-1 resize-none bg-transparent px-2 py-1.5 text-[15px] outline-none placeholder:text-muted max-h-40"
        onChange={(e) => {
          setText(e.target.value);
          e.target.style.height = "auto";
          e.target.style.height = `${Math.min(e.target.scrollHeight, 160)}px`;
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <motion.button
        whileTap={{ scale: 0.9 }}
        whileHover={{ scale: 1.06 }}
        onClick={submit}
        disabled={disabled || !text.trim()}
        aria-label="Send"
        className="grid h-9 w-9 place-items-center rounded-xl bg-accent text-white disabled:opacity-40 disabled:cursor-not-allowed"
      >
        <SendHorizonal size={16} />
      </motion.button>
    </div>
  );
}
