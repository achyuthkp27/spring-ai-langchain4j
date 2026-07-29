"use client";

import { useImperativeHandle, useRef, useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import { ArrowUp, Plus, Square } from "lucide-react";

const MAX_LENGTH = 4000;
const COUNTER_THRESHOLD = 3500;
const MAX_TEXTAREA_PX = 200;

export interface ComposerHandle {
  fill: (prompt: string) => void;
}

/**
 * Same component in both places it renders (see ChatShell) — the hero variant
 * just runs a little larger and picks up an ambient brand glow to hold its
 * own in the empty-state's open space; the docked variant stays compact.
 */
const SIZING = {
  hero: {
    shell: "rounded-[26px]",
    shadow: "0 30px 70px -34px rgba(0,0,0,0.65)",
    textarea: "px-5 pt-4 pb-1.5 text-body",
    bar: "px-3.5 pb-3 pt-2",
    button: "h-10 w-10",
    icon: 18,
  },
  docked: {
    shell: "rounded-[22px]",
    shadow: "0 18px 44px -28px rgba(0,0,0,0.6)",
    textarea: "px-4 pt-3.5 pb-1",
    bar: "px-3 pb-2.5 pt-1.5",
    button: "h-9 w-9",
    icon: 17,
  },
} as const;

export function Composer({
  onSend,
  onNewChat,
  onStop,
  disabled,
  inputRef,
  variant = "docked",
}: {
  onSend: (text: string) => void;
  onNewChat: () => void;
  onStop?: () => void;
  disabled: boolean;
  inputRef?: React.RefObject<ComposerHandle | null>;
  variant?: "hero" | "docked";
}) {
  const [text, setText] = useState("");
  const [focused, setFocused] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const reduceMotion = useReducedMotion();
  const size = SIZING[variant];

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
  const canSend = !!text.trim() && !disabled;
  const tap = reduceMotion ? undefined : { scale: 0.92 };
  const hover = reduceMotion ? undefined : { scale: 1.05 };

  return (
    <div className="relative">
      {variant === "hero" && (
        <div
          aria-hidden
          className="pointer-events-none absolute -inset-x-10 -inset-y-8 rounded-[40px] opacity-80 blur-2xl"
          style={{
            background:
              "radial-gradient(60% 120% at 22% 0%, var(--glow-a), transparent 70%), radial-gradient(50% 100% at 85% 100%, var(--glow-b), transparent 72%)",
          }}
        />
      )}

      <div
        className={clsx(
          "panel lit relative overflow-hidden border transition-colors duration-200",
          size.shell,
          focused ? "border-accent/45" : "border-hairline",
        )}
        style={{
          boxShadow: focused
            ? `0 0 0 1px var(--glow-a), 0 0 32px -6px var(--glow-a), ${size.shadow}`
            : size.shadow,
          transition: "box-shadow 220ms ease, border-color 220ms ease",
        }}
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
          className={clsx(
            "scroll-slim block w-full resize-none bg-transparent outline-none placeholder:text-muted",
            size.textarea,
          )}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
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

        <div className={clsx("flex items-center gap-2 border-t border-hairline/70", size.bar)}>
          <motion.button
            type="button"
            onClick={onNewChat}
            aria-label="Start a new chat"
            whileTap={tap}
            whileHover={hover}
            className={clsx(
              "grid shrink-0 place-items-center rounded-full border border-hairline/80 bg-surface-2/50 text-muted transition-colors hover:border-accent/40 hover:bg-surface-2 hover:text-foreground",
              size.button,
            )}
          >
            <Plus size={size.icon - 1} />
          </motion.button>

          <span className="hidden min-w-0 flex-1 items-center gap-1 truncate pl-0.5 text-micro text-muted/75 sm:flex">
            <kbd className="rounded-[5px] border border-hairline bg-surface-2/70 px-[5px] py-0.5 font-mono text-[10px] leading-none">
              Enter
            </kbd>
            to send
            <span className="mx-0.5 text-muted/40">·</span>
            <kbd className="rounded-[5px] border border-hairline bg-surface-2/70 px-[5px] py-0.5 font-mono text-[10px] leading-none">
              Shift+Enter
            </kbd>
            new line
          </span>
          <span className="flex-1 sm:hidden" />

          {showCounter && (
            <span
              className={clsx(
                "shrink-0 rounded-full px-1.5 py-0.5 text-micro tabular-nums",
                text.length >= MAX_LENGTH
                  ? "bg-critical-soft text-critical-ink"
                  : "text-muted",
              )}
              aria-live="polite"
            >
              {text.length}/{MAX_LENGTH}
            </span>
          )}

          {disabled ? (
            <motion.button
              type="button"
              onClick={onStop}
              disabled={!onStop}
              aria-label="Stop generating"
              whileTap={tap}
              whileHover={hover}
              animate={
                reduceMotion
                  ? undefined
                  : { boxShadow: ["0 0 0px 0px var(--glow-a)", "0 0 0 6px transparent"] }
              }
              transition={reduceMotion ? undefined : { duration: 1.4, repeat: Infinity, ease: "easeOut" }}
              className={clsx(
                "grid shrink-0 place-items-center rounded-full bg-surface-2 text-foreground transition-colors hover:bg-border-soft disabled:cursor-not-allowed disabled:opacity-40",
                size.button,
              )}
            >
              <Square size={12} fill="currentColor" />
            </motion.button>
          ) : (
            <motion.button
              type="button"
              onClick={submit}
              disabled={!canSend}
              aria-label="Send message"
              whileTap={canSend ? tap : undefined}
              whileHover={canSend ? hover : undefined}
              className={clsx(
                "grid shrink-0 place-items-center rounded-full transition-colors",
                size.button,
                canSend
                  ? "bg-accent-strong text-on-accent shadow-[0_0_22px_-5px_var(--glow-a)]"
                  : "cursor-not-allowed bg-surface-2 text-muted",
              )}
            >
              <ArrowUp size={size.icon} strokeWidth={2.4} />
            </motion.button>
          )}
        </div>
      </div>
    </div>
  );
}
