"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import clsx from "clsx";
import {
  AlertTriangle,
  ArrowDown,
  Clock,
  CreditCard,
  Landmark,
  ListChecks,
  Menu,
  Moon,
  RefreshCw,
  ShieldQuestion,
  Sun,
  Wallet,
} from "lucide-react";
import { useChatStream } from "@/hooks/useChatStream";
import { useConversations } from "@/hooks/useConversations";
import { useSession } from "@/hooks/useSession";
import { MessageBubble } from "./MessageBubble";
import { TypingIndicator } from "./TypingIndicator";
import { StatusChips } from "./StatusChips";
import { Composer } from "./Composer";
import { Sidebar } from "./Sidebar";

const SUGGESTIONS = [
  { text: "What's my balance?", icon: Wallet, tone: "accent" as const },
  { text: "Show my recent transactions", icon: ListChecks, tone: "system" as const },
  { text: "I don't recognize a charge — dispute it", icon: ShieldQuestion, tone: "warning" as const },
  { text: "Freeze my card, I lost it", icon: CreditCard, tone: "good" as const },
  { text: "What's the dispute filing deadline?", icon: Clock, tone: "accent" as const },
];

export function ChatShell() {
  const { profile, bankName, switchTo } = useSession();
  const identityKey = profile ? `${profile.tenantId}:${profile.userId}` : null;
  const { conversations, activeId, setActiveId, create, titleFrom, remove } = useConversations(identityKey);
  const { messages, statuses, busy, historyError, send, loadHistory } = useChatStream(activeId);
  const [menuOpen, setMenuOpen] = useState(false);
  const [dark, setDark] = useState(false);
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const nearBottomRef = useRef(true);

  useEffect(() => {
    setDark(document.documentElement.classList.contains("dark"));
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    if (nearBottomRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages, statuses]);

  const handleScroll = () => {
    const el = mainRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distanceFromBottom < 120;
    nearBottomRef.current = atBottom;
    setShowScrollBtn(!atBottom);
  };

  const scrollToBottom = () => bottomRef.current?.scrollIntoView({ behavior: "smooth" });

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        create();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [create]);

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
        profile={profile}
        onSwitchIdentity={switchTo}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        {}
        <header className="flex items-center gap-3 px-4 py-3">
          <button
            className="md:hidden rounded-full p-1.5 text-muted hover:bg-pill transition-colors"
            aria-label="Menu"
            onClick={() => setMenuOpen(true)}
          >
            <Menu size={18} />
          </button>
          <div className="flex-1 min-w-0">
            <h1 className="text-[13px] font-semibold leading-tight">Achu FinBot</h1>
            <p className="truncate text-[11px] text-muted leading-tight">
              {profile ? `Assistant for ${bankName}` : "Your banking assistant"}
            </p>
          </div>
          <button
            onClick={toggleTheme}
            aria-label="Toggle theme"
            className="rounded-full p-1.5 text-muted hover:bg-pill transition-colors"
          >
            {dark ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </header>

        {}
        <main ref={mainRef} onScroll={handleScroll} className="relative flex-1 overflow-y-auto px-4 py-5">
          <div
            className="mx-auto flex max-w-2xl flex-col gap-3"
            aria-live="polite"
            aria-relevant="additions text"
          >
            {messages.length === 0 && (
              <motion.div
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                className="mt-16 text-center sm:mt-24"
              >
                <div className="mx-auto mb-5 grid h-11 w-11 place-items-center rounded-full border border-pill-border bg-pill backdrop-blur-sm">
                  <Landmark size={18} className="text-accent" />
                </div>
                <h2 className="text-[17px] font-semibold tracking-tight">
                  How can I help with your {bankName} banking?
                </h2>
                <p className="mt-1.5 text-[13px] text-muted">
                  Accounts, cards, disputes, and policy questions.
                </p>
              </motion.div>
            )}

            {historyError && (
              <motion.div
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex items-center justify-between gap-3 rounded-xl bg-warning-soft px-3.5 py-2.5 text-sm text-warning"
              >
                <span className="flex items-center gap-2">
                  <AlertTriangle size={15} /> Couldn&apos;t load this conversation&apos;s history.
                </span>
                <button
                  onClick={() => loadHistory()}
                  className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs font-medium hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
                >
                  <RefreshCw size={12} /> Retry
                </button>
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

          <AnimatePresence>
            {showScrollBtn && (
              <motion.button
                initial={{ opacity: 0, y: 8, scale: 0.9 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 8, scale: 0.9 }}
                whileHover={{ scale: 1.06 }}
                whileTap={{ scale: 0.94 }}
                onClick={scrollToBottom}
                aria-label="Scroll to latest message"
                className="absolute bottom-4 left-1/2 -translate-x-1/2 grid h-9 w-9 place-items-center rounded-full border border-border-soft bg-surface text-muted shadow-lg hover:text-foreground transition-colors"
              >
                <ArrowDown size={16} />
              </motion.button>
            )}
          </AnimatePresence>
        </main>

        {}
        <footer className="px-4 pb-4 pt-1">
          <div className="mx-auto max-w-2xl">
            <Composer onSend={handleSend} onNewChat={create} disabled={busy} />

            {messages.length === 0 && (
              <div className="mt-3 flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s.text}
                    onClick={() => handleSend(s.text)}
                    className="flex shrink-0 items-center gap-2 rounded-full border border-pill-border bg-pill py-1.5 pl-1.5 pr-3.5 text-[13px] backdrop-blur-sm hover:bg-pill/70 transition-colors"
                  >
                    <span
                      className={clsx(
                        "grid h-6 w-6 shrink-0 place-items-center rounded-lg border bg-surface/40",
                        s.tone === "accent" && "border-accent/40 text-accent",
                        s.tone === "system" && "border-system/40 text-system",
                        s.tone === "good" && "border-good/40 text-good",
                        s.tone === "warning" && "border-warning/40 text-warning",
                      )}
                    >
                      <s.icon size={12} />
                    </span>
                    {s.text}
                  </button>
                ))}
              </div>
            )}

            <p className="mt-2 text-center text-[11px] text-muted">
              Achu FinBot can check accounts and open disputes, but never moves money without staff approval.
            </p>
          </div>
        </footer>
      </div>
    </div>
  );
}
