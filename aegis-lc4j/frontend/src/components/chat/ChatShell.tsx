"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import {
  AlertTriangle,
  ArrowDown,
  CreditCard,
  Menu,
  Moon,
  RefreshCw,
  ScrollText,
  ShieldQuestion,
  Sun,
} from "lucide-react";
import { useChatStream } from "@/hooks/useChatStream";
import { useConversations } from "@/hooks/useConversations";
import { useSession } from "@/hooks/useSession";
import { BrandBeacon, BrandMark } from "@/components/BrandMark";
import { Splash } from "@/components/Splash";
import { MessageBubble } from "./MessageBubble";
import { TypingIndicator } from "./TypingIndicator";
import { StatusChips } from "./StatusChips";
import { Composer, type ComposerHandle } from "./Composer";
import { Sidebar } from "./Sidebar";

const CAPABILITIES = [
  {
    icon: CreditCard,
    title: "Accounts & cards",
    body: "Check balances, review activity, freeze a card or set a limit — instantly.",
    prompt: "Show me my accounts and cards",
  },
  {
    icon: ShieldQuestion,
    title: "Disputes & fraud",
    body: "Flag a charge you don't recognise and I'll open the case end to end.",
    prompt: "I don't recognize a charge — help me dispute it",
  },
  {
    icon: ScrollText,
    title: "Policies & limits",
    body: "Deadlines, fees and procedures, answered from the bank's own documents.",
    prompt: "What's the dispute filing deadline?",
  },
];

export function ChatShell() {
  const { profile, bankName, switchTo } = useSession();
  const identityKey = profile ? `${profile.tenantId}:${profile.userId}` : null;
  const { conversations, activeId, setActiveId, create, titleFrom, remove } =
    useConversations(identityKey);
  const { messages, statuses, busy, historyError, send, stop, loadHistory } = useChatStream(activeId);
  const [menuOpen, setMenuOpen] = useState(false);
  const [dark, setDark] = useState(true);
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const nearBottomRef = useRef(true);
  const composerRef = useRef<ComposerHandle>(null);
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    setDark(document.documentElement.classList.contains("dark"));
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    if (nearBottomRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: busy || reduceMotion ? "auto" : "smooth" });
    }
  }, [messages, statuses, busy, reduceMotion]);

  const handleScroll = () => {
    const el = mainRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distanceFromBottom < 120;
    nearBottomRef.current = atBottom;
    setShowScrollBtn(!atBottom);
  };

  const scrollToBottom = () =>
    bottomRef.current?.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth" });

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

  const isEmpty = messages.length === 0;
  const lastIsStreamingEmpty = busy && messages.length > 0 && messages[messages.length - 1].text === "";

  const lastMessage = messages[messages.length - 1];
  const liveAnnouncement =
    lastMessage?.role === "assistant" && lastMessage.streaming
      ? "Assistant is responding…"
      : lastMessage?.role === "assistant" && !lastMessage.streaming
        ? lastMessage.text
        : "";

  const composer = (
    <Composer
      onSend={handleSend}
      onNewChat={create}
      onStop={stop}
      disabled={busy}
      inputRef={composerRef}
    />
  );

  const errorBanner = historyError && (
    <div className="mx-auto flex max-w-2xl items-center justify-between gap-3 rounded-xl border border-warning/25 bg-warning-soft px-3.5 py-2.5 text-label text-warning-ink">
      <span className="flex items-center gap-2">
        <AlertTriangle size={15} className="shrink-0 text-warning" />
        Couldn&apos;t load this conversation&apos;s history.
      </span>
      <button
        onClick={() => loadHistory()}
        className="flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1 text-micro font-medium transition-colors hover:bg-foreground/5"
      >
        <RefreshCw size={12} /> Retry
      </button>
    </div>
  );

  return (
    <div className="relative flex h-dvh overflow-hidden">
      <Splash />
      <div className="canvas-bloom" aria-hidden />
      <div className="canvas-grain" aria-hidden />

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

      <div className="relative z-10 flex min-w-0 flex-1 flex-col">
        <header className="flex shrink-0 items-center gap-3 border-b border-hairline px-4 py-3">
          <button
            className="grid h-10 w-10 place-items-center rounded-xl text-muted transition-colors hover:bg-surface-2 hover:text-foreground md:hidden"
            aria-label="Open menu"
            onClick={() => setMenuOpen(true)}
          >
            <Menu size={18} />
          </button>
          <div className="flex min-w-0 flex-1 items-center gap-2.5">
            <BrandMark size={17} gradientId="header-mark" className="shrink-0 md:hidden" />
            <div className="min-w-0">
              <h1 className="truncate text-label font-semibold leading-tight">Achu FinBot</h1>
              <p className="truncate text-micro text-muted">
                {profile ? `Assistant for ${bankName}` : "Signing you in…"}
              </p>
            </div>
          </div>
          <button
            onClick={toggleTheme}
            aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
            className="grid h-10 w-10 place-items-center rounded-xl text-muted transition-colors hover:bg-surface-2 hover:text-foreground"
          >
            <Sun size={16} className="hidden dark:block" />
            <Moon size={16} className="dark:hidden" />
          </button>
        </header>

        <div aria-live="polite" className="sr-only">
          {liveAnnouncement}
        </div>

        {isEmpty ? (
          /* ---- Hero: the composer sits centred until the first turn ---- */
          <main className="scroll-slim flex-1 overflow-y-auto px-4">
            <div className="mx-auto flex min-h-full w-full max-w-2xl flex-col justify-center py-10">
              {errorBanner && <div className="mb-6">{errorBanner}</div>}

              <BrandBeacon size={72} gradientId="hero-beacon" />

              <div className="relative select-none py-12 text-center">
                <span
                  aria-hidden
                  className="pointer-events-none absolute inset-x-0 top-0 text-3xl font-semibold tracking-tight text-foreground/[0.06] sm:text-4xl"
                >
                  Understand
                </span>
                <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">
                  Ask anything about your money
                </h2>
                <span
                  aria-hidden
                  className="pointer-events-none absolute inset-x-0 bottom-0 text-3xl font-semibold tracking-tight text-foreground/[0.06] sm:text-4xl"
                >
                  Resolve
                </span>
              </div>

              {composer}

              <div className="mt-4 grid gap-3 sm:grid-cols-3">
                {CAPABILITIES.map((c, i) => (
                  <motion.button
                    key={c.title}
                    type="button"
                    initial={reduceMotion ? false : { opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: reduceMotion ? 0 : 0.06 * i, duration: 0.35 }}
                    onClick={() => composerRef.current?.fill(c.prompt)}
                    className="panel lit group rounded-xl p-3.5 text-left transition-colors hover:border-accent/35"
                  >
                    <span className="grid h-8 w-8 place-items-center rounded-lg bg-surface-2 text-accent transition-colors group-hover:bg-accent-soft">
                      <c.icon size={15} />
                    </span>
                    <p className="mt-2.5 text-label font-semibold">{c.title}</p>
                    <p className="mt-1 text-micro leading-relaxed text-muted">{c.body}</p>
                  </motion.button>
                ))}
              </div>

              <p className="mt-8 text-center text-micro leading-relaxed text-muted/75">
                Achu FinBot can check accounts, freeze cards and open disputes. It never moves money
                without your confirmation and bank-staff approval. Every action is audited.
              </p>
            </div>
          </main>
        ) : (
          <>
            <main
              ref={mainRef}
              onScroll={handleScroll}
              className="scroll-slim relative flex-1 overflow-y-auto px-4 py-6"
            >
              <div className="mx-auto flex max-w-2xl flex-col gap-4">
                {errorBanner}

                {messages.map((m) =>
                  m.role === "assistant" && m.text === "" && m.streaming ? null : (
                    <MessageBubble key={m.id} message={m} />
                  ),
                )}

                <AnimatePresence>{lastIsStreamingEmpty && <TypingIndicator />}</AnimatePresence>
                {statuses.length > 0 && <StatusChips statuses={statuses} />}
                <div ref={bottomRef} className="h-px" />
              </div>

              <AnimatePresence>
                {showScrollBtn && (
                  <motion.button
                    initial={reduceMotion ? false : { opacity: 0, y: 8, scale: 0.9 }}
                    animate={{ opacity: 1, y: 0, scale: 1 }}
                    exit={{ opacity: 0, y: 8, scale: 0.9 }}
                    onClick={scrollToBottom}
                    aria-label="Scroll to latest message"
                    className="glass sticky bottom-2 left-1/2 grid h-10 w-10 -translate-x-1/2 place-items-center rounded-full text-muted shadow-lg transition-colors hover:text-foreground"
                  >
                    <ArrowDown size={16} />
                  </motion.button>
                )}
              </AnimatePresence>
            </main>

            <footer className="shrink-0 px-4 pb-4 pt-1">
              <div className="mx-auto max-w-2xl">
                {composer}
                <p className="mt-2 text-center text-micro text-muted/70">
                  Achu FinBot never moves money without your confirmation and staff approval.
                </p>
              </div>
            </footer>
          </>
        )}
      </div>
    </div>
  );
}
