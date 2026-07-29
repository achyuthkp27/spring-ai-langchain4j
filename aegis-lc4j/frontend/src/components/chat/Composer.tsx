"use client";

import { useImperativeHandle, useRef, useState } from "react";
import clsx from "clsx";
import { ArrowUp, Plus, Square } from "lucide-react";

const MAX_LENGTH = 4000;
const COUNTER_THRESHOLD = 3500;
const MAX_TEXTAREA_PX = 200;

export interface ComposerHandle {
  fill: (prompt: string) => void;
}

export function Composer({
  onSend,
  onNewChat,
  onStop,
  disabled,
  inputRef,
}: {
  onSend: (text: string) => void;
  onNewChat: () => void;
  onStop?: () => void;
  disabled: boolean;
  inputRef?: React.RefObject<ComposerHandle | null>;
}) {
  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  const resize = (el: HTMLTextAreaElement) => {
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, MAX_TEXTAREA_PX)}px`;
  };

  const submit = () => {
    const t = text.trim();
    if (!t || disabled) return;
    onSend(t);
    setText("");
    if (ref.current) ref.current.style.height = "auto";
  };

  useImperativeHandle(inputRef, () => ({
    fill: (prompt: string) => {
      setText(prompt);
      const el = ref.current;
      if (!el) return;
      el.focus();
      requestAnimationFrame(() => resize(el));
    },
  }));

  const showCounter = text.length >= COUNTER_THRESHOLD;

  return (
    <div
      className="panel lit overflow-hidden rounded-2xl transition-colors duration-200 focus-within:border-accent/45"
      style={{ boxShadow: "0 18px 44px -28px rgba(0,0,0,0.6)" }}
    >
      <label htmlFor="composer-input" className="sr-only">
          Message Achu FinBot
        </label>
        <textarea
          id="composer-input"
          ref={ref}
          value={text}
          rows={1}
          maxLength={MAX_LENGTH}
          placeholder="Ask about your accounts, cards, disputes or our policies…"
          className="scroll-slim block w-full resize-none bg-transparent px-4 pb-1 pt-3.5 text-body outline-none placeholder:text-muted"
          onChange={(e) => {
            setText(e.target.value);
            resize(e.target);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
        />

        <div className="flex items-center gap-2 px-2.5 pb-2.5 pt-1">
          <button
            type="button"
            onClick={onNewChat}
            aria-label="Start a new chat"
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-foreground"
          >
            <Plus size={17} />
          </button>

          <span className="hidden min-w-0 flex-1 truncate pl-0.5 text-micro text-muted/80 sm:block">
            Enter to send · Shift+Enter for a new line
          </span>
          <span className="flex-1 sm:hidden" />

          {showCounter && (
            <span
              className={clsx(
                "shrink-0 text-micro tabular-nums",
                text.length >= MAX_LENGTH ? "text-critical-ink" : "text-muted",
              )}
              aria-live="polite"
            >
              {text.length}/{MAX_LENGTH}
            </span>
          )}

          {disabled ? (
            <button
              type="button"
              onClick={onStop}
              disabled={!onStop}
              aria-label="Stop generating"
              className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-surface-2 text-foreground transition-colors hover:bg-border-soft disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Square size={12} fill="currentColor" />
            </button>
          ) : (
            <button
              type="button"
              onClick={submit}
              disabled={!text.trim()}
              aria-label="Send message"
              className={clsx(
                "grid h-9 w-9 shrink-0 place-items-center rounded-full transition-all",
                text.trim()
                  ? "bg-accent-strong text-on-accent shadow-[0_0_20px_-4px_var(--glow-a)]"
                  : "cursor-not-allowed bg-surface-2 text-muted",
              )}
            >
              <ArrowUp size={17} strokeWidth={2.4} />
            </button>
          )}
      </div>
    </div>
  );
}
