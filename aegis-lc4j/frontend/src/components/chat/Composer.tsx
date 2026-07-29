"use client";

import { useRef, useState } from "react";
import clsx from "clsx";
import { Plus, SendHorizonal, Square } from "lucide-react";

const MAX_LENGTH = 4000;
const COUNTER_THRESHOLD = 3500;

export function Composer({
  onSend,
  onNewChat,
  onStop,
  disabled,
}: {
  onSend: (text: string) => void;
  onNewChat: () => void;
  onStop?: () => void;
  disabled: boolean;
}) {
  const [text, setText] = useState("");
  const [focused, setFocused] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    const t = text.trim();
    if (!t || disabled) return;
    onSend(t);
    setText("");
    if (ref.current) ref.current.style.height = "auto";
  };

  return (
    <div>
      <div
        className={clsx(
          "flex items-end gap-2 rounded-full border bg-pill p-1.5 pl-2 backdrop-blur-sm transition-colors duration-150",
          focused ? "border-accent/50" : "border-pill-border",
        )}
      >
        <button
          onClick={onNewChat}
          aria-label="Start a new chat"
          className="grid h-8 w-8 shrink-0 place-items-center self-center rounded-full bg-surface text-muted hover:text-foreground transition-colors"
        >
          <Plus size={16} />
        </button>
        <textarea
          ref={ref}
          value={text}
          rows={1}
          maxLength={MAX_LENGTH}
          placeholder="Chat here.."
          className="flex-1 resize-none bg-transparent px-1 py-2 text-[15px] outline-none placeholder:text-muted max-h-40"
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
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
        {disabled ? (
          <button
            onClick={onStop}
            disabled={!onStop}
            aria-label="Stop generating"
            className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent text-white transition-opacity hover:opacity-90 disabled:opacity-30 disabled:cursor-not-allowed"
          >
            <Square size={13} />
          </button>
        ) : (
          <button
            onClick={submit}
            disabled={!text.trim()}
            aria-label="Send"
            className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent text-white transition-opacity hover:opacity-90 disabled:opacity-30 disabled:cursor-not-allowed"
          >
            <SendHorizonal size={15} />
          </button>
        )}
      </div>
      {text.length >= COUNTER_THRESHOLD && (
        <p className="mt-1 text-right text-[11px] text-muted">
          {text.length}/{MAX_LENGTH}
        </p>
      )}
    </div>
  );
}
