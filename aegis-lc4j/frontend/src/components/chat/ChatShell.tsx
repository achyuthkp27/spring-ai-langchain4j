"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Landmark, Menu, Moon, Sun } from "lucide-react";
import { useChatStream } from "@/hooks/useChatStream";
import { useConversations } from "@/hooks/useConversations";
import { MessageBubble } from "./MessageBubble";
import { TypingIndicator } from "./TypingIndicator";
import { StatusChips } from "./StatusChips";
import { Composer } from "./Composer";
import { Sidebar } from "./Sidebar";

const SUGGESTIONS = [
  "What's my balance?",
  "Show my recent transactions",
  "I don't recognize a charge — help me dispute it",
  "Freeze my card, I lost it",
  "What's the dispute filing deadline?",
];

export function ChatShell() {
  const { conversations, activeId, setActiveId, create, titleFrom, remove } = useConversations();
  const { messages, statuses, busy, send, loadHistory } = useChatStream(activeId);
  const [menuOpen, setMenuOpen] = useState(false);
  const [dark, setDark] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setDark(document.documentElement.classList.contains("dark"));
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, statuses]);

  const toggleTheme = () => {
    const next = !dark;
    setDark(next);
    document.documentElement.classList.toggle("dark", next);
    localStorage.setItem("aegis.theme", next ? "dark" : "light");
  };

  const handleSend = (text: string) => {
    titleFrom(activeId, text);
    send(text);
  };

  const lastIsStreamingEmpty =
    busy && messages.length > 0 && messages[messages.length - 1].text === "";

  return (
    <div className="flex h-dvh">
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        conversations={conversations}
        activeId={activeId}
        onSelect={setActiveId}
        onCreate={create}
        onRemove={remove}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Header */}
        <header className="flex items-center gap-3 border-b border-border-soft bg-surface/80 backdrop-blur px-4 py-3">
          <button className="md:hidden text-muted" aria-label="Menu" onClick={() => setMenuOpen(true)}>
            <Menu size={20} />
          </button>
          <div className="grid h-8 w-8 place-items-center rounded-xl bg-accent text-white">
            <Landmark size={16} />
          </div>
          <div className="flex-1">
            <h1 className="text-sm font-semibold leading-tight">Aegis</h1>
            <p className="text-[11px] text-muted leading-tight">Your banking assistant</p>
          </div>
          <button onClick={toggleTheme} aria-label="Toggle theme" className="text-muted hover:text-foreground transition-colors">
            {dark ? <Sun size={18} /> : <Moon size={18} />}
          </button>
        </header>

        {/* Messages */}
        <main className="flex-1 overflow-y-auto px-4 py-5">
          <div className="mx-auto flex max-w-2xl flex-col gap-3">
            {messages.length === 0 && (
              <motion.div
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                className="mt-16 text-center"
              >
                <div className="mx-auto mb-4 grid h-14 w-14 place-items-center rounded-2xl bg-accent-soft text-accent">
                  <Landmark size={26} />
                </div>
                <h2 className="text-lg font-semibold">Hi! How can I help with your banking?</h2>
                <p className="mt-1 text-sm text-muted">
                  Accounts, cards, disputes, and policy questions — I&apos;m on it.
                </p>
                <div className="mt-6 flex flex-wrap justify-center gap-2">
                  {SUGGESTIONS.map((s, i) => (
                    <motion.button
                      key={s}
                      initial={{ opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: 0.15 + i * 0.06 }}
                      whileHover={{ scale: 1.04 }}
                      whileTap={{ scale: 0.97 }}
                      onClick={() => handleSend(s)}
                      className="rounded-full border border-border-soft bg-surface px-3.5 py-1.5 text-[13px] hover:border-accent hover:text-accent transition-colors"
                    >
                      {s}
                    </motion.button>
                  ))}
                </div>
              </motion.div>
            )}

            {messages.map((m) =>
              m.role === "assistant" && m.text === "" && m.streaming ? null : (
                <MessageBubble key={m.id} message={m} />
              ),
            )}

            <AnimatePresence>{lastIsStreamingEmpty && <TypingIndicator />}</AnimatePresence>
            {statuses.length > 0 && <StatusChips statuses={statuses} />}
            <div ref={bottomRef} />
          </div>
        </main>

        {/* Composer */}
        <footer className="border-t border-border-soft bg-surface/60 backdrop-blur px-4 py-3">
          <div className="mx-auto max-w-2xl">
            <Composer onSend={handleSend} disabled={busy} />
            <p className="mt-1.5 text-center text-[11px] text-muted">
              Aegis can check accounts and open disputes, but never moves money without staff approval.
            </p>
          </div>
        </footer>
      </div>
    </div>
  );
}
