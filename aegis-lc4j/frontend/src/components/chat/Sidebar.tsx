"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import {
  ChevronDown,
  LayoutDashboard,
  MessageSquare,
  MessageSquarePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Trash2,
  X,
} from "lucide-react";
import Link from "next/link";
import clsx from "clsx";
import { useEffect, useMemo, useRef, useState } from "react";
import type { Conversation } from "@/hooks/useConversations";
import { DEMO_IDENTITIES } from "@/hooks/useSession";
import { displayName } from "@/lib/tenantNames";
import type { Profile } from "@/lib/api";
import { BrandMark } from "@/components/BrandMark";

function groupByDate(conversations: Conversation[]): [string, Conversation[]][] {
  const now = new Date();
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const today = startOfDay(now);
  const yesterday = today - 86_400_000;
  const weekAgo = today - 7 * 86_400_000;

  const buckets = new Map<string, Conversation[]>();
  const order = ["Today", "Yesterday", "Previous 7 days", "Older"];
  order.forEach((k) => buckets.set(k, []));

  for (const c of conversations) {
    const day = startOfDay(new Date(c.createdAt));
    const key =
      day >= today ? "Today" : day >= yesterday ? "Yesterday" : day >= weekAgo ? "Previous 7 days" : "Older";
    buckets.get(key)!.push(c);
  }
  return order
    .map((k): [string, Conversation[]] => [k, buckets.get(k)!])
    .filter(([, list]) => list.length > 0);
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input, textarea, select, [tabindex]:not([tabindex="-1"])';

export function Sidebar({
  open,
  onClose,
  conversations,
  activeId,
  onSelect,
  onCreate,
  onRemove,
  profile,
  onSwitchIdentity,
}: {
  open: boolean;
  onClose: () => void;
  conversations: Conversation[];
  activeId: string;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onRemove: (id: string) => void;
  profile: Profile | null;
  onSwitchIdentity: (tenantId: string, userId: string) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const reduceMotion = useReducedMotion();

  const filtered = useMemo(
    () =>
      query.trim()
        ? conversations.filter((c) => c.title.toLowerCase().includes(query.trim().toLowerCase()))
        : conversations,
    [conversations, query],
  );
  const grouped = useMemo(() => groupByDate(filtered), [filtered]);

  /* Mobile drawer: trap focus while open, close on Escape, restore focus to
     whatever opened it. Without this, Tab walks straight into the page behind. */
  useEffect(() => {
    if (!open) return;
    restoreFocusRef.current = document.activeElement as HTMLElement | null;
    const node = drawerRef.current;
    node?.querySelector<HTMLElement>(FOCUSABLE)?.focus();

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab" || !node) return;
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (el) => el.offsetParent !== null,
      );
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      restoreFocusRef.current?.focus?.();
    };
  }, [open, onClose]);

  const navItem = (collapsedLayout: boolean) =>
    clsx(
      "flex items-center gap-3 rounded-xl border border-transparent py-2.5 text-label font-medium text-muted transition-colors hover:bg-surface-2 hover:text-foreground",
      collapsedLayout ? "justify-center px-2.5" : "px-3",
    );

  const body = (
    <div
      className={clsx(
        "flex h-full flex-col gap-3 border-r border-hairline bg-background/60 p-3 backdrop-blur-xl transition-[width] duration-200",
        collapsed ? "w-[76px]" : "w-72",
      )}
    >
      <div className="flex items-center justify-between gap-2 px-1 py-1.5">
        {!collapsed && (
          <span className="flex min-w-0 items-center gap-2">
            <BrandMark size={19} glow gradientId="sidebar-mark" className="shrink-0" />
            <span className="min-w-0">
              <span className="block truncate text-label font-semibold tracking-tight">
                Achu FinBot
              </span>
              <span className="block truncate text-[10px] uppercase tracking-[0.14em] text-muted">
                {profile ? displayName(profile.tenantId) : "Banking"}
              </span>
            </span>
          </span>
        )}
        <button
          onClick={() => setCollapsed((c) => !c)}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="ml-auto hidden h-9 w-9 shrink-0 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-foreground md:grid"
        >
          {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
        </button>
      </div>

      <div className="flex flex-col gap-1">
        <button onClick={onCreate} className={navItem(collapsed)}>
          <MessageSquarePlus size={16} className="shrink-0 text-accent" />
          {!collapsed && <span>New chat</span>}
        </button>
        <Link href="/admin" className={navItem(collapsed)}>
          <LayoutDashboard size={16} className="shrink-0 text-system" />
          {!collapsed && <span>Admin</span>}
        </Link>
      </div>

      {!collapsed && (
        <>
          <div className="relative">
            <Search
              size={14}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
            />
            <label htmlFor="conversation-search" className="sr-only">
              Search conversations
            </label>
            <input
              id="conversation-search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search"
              className="w-full rounded-xl border border-hairline bg-surface-2/60 py-2 pl-9 pr-3 text-label outline-none transition-colors placeholder:text-muted focus:border-accent/45"
            />
          </div>

          <div className="scroll-slim -mx-1 flex-1 overflow-y-auto px-1">
            {grouped.map(([label, list]) => (
              <div key={label} className="mb-3">
                <p className="px-2 pb-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted/80">
                  {label}
                </p>
                {list.map((c) => {
                  const active = c.id === activeId;
                  return (
                    <div
                      key={c.id}
                      className={clsx(
                        "group relative mb-0.5 flex items-center rounded-xl transition-colors",
                        active
                          ? "border border-hairline bg-surface-2"
                          : "border border-transparent hover:bg-surface-2/60",
                      )}
                    >
                      <button
                        type="button"
                        onClick={() => {
                          onSelect(c.id);
                          onClose();
                        }}
                        aria-current={active ? "page" : undefined}
                        className="flex min-w-0 flex-1 items-center gap-2.5 rounded-xl py-2 pl-3 pr-9 text-left text-label"
                      >
                        <MessageSquare
                          size={13}
                          className={clsx("shrink-0", active ? "text-accent" : "text-muted")}
                        />
                        <span className={clsx("truncate", active && "font-medium")}>{c.title}</span>
                      </button>
                      <button
                        type="button"
                        aria-label={`Delete conversation "${c.title}"`}
                        className="absolute right-1 grid h-8 w-8 place-items-center rounded-lg text-muted opacity-0 transition-opacity hover:bg-critical-soft hover:text-critical-ink focus-visible:opacity-100 group-hover:opacity-100"
                        onClick={() => {
                          if (window.confirm(`Delete "${c.title}"? This can't be undone.`)) {
                            onRemove(c.id);
                          }
                        }}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  );
                })}
              </div>
            ))}
            {filtered.length === 0 && (
              <p className="px-3 py-2 text-micro text-muted">
                {query ? "No matching chats." : "No conversations yet."}
              </p>
            )}
          </div>

          <div className="relative">
            <button
              onClick={() => setPickerOpen((o) => !o)}
              aria-expanded={pickerOpen}
              className="flex w-full items-center justify-between gap-2 rounded-xl border border-hairline bg-surface-2/60 px-3 py-2.5 text-left transition-colors hover:bg-surface-2"
            >
              <span className="flex min-w-0 items-center gap-2.5">
                <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-accent-soft text-micro font-semibold uppercase text-accent-ink">
                  {profile ? profile.userId.slice(0, 2) : "··"}
                </span>
                <span className="min-w-0">
                  <span className="block truncate text-label font-medium">
                    {profile ? profile.userId : "Signing in…"}
                  </span>
                  <span className="block truncate text-micro text-muted">
                    {profile ? displayName(profile.tenantId) : ""}
                  </span>
                </span>
              </span>
              <ChevronDown
                size={14}
                className={clsx("shrink-0 text-muted transition-transform", pickerOpen && "rotate-180")}
              />
            </button>
            <AnimatePresence>
              {pickerOpen && (
                <motion.div
                  initial={reduceMotion ? false : { opacity: 0, y: 6, scale: 0.98 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 6, scale: 0.98 }}
                  transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
                  className="panel lit absolute bottom-full left-0 z-10 mb-2 w-full p-1.5 shadow-2xl"
                >
                  <p className="px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted">
                    Demo identity
                  </p>
                  {DEMO_IDENTITIES.map((identity) => (
                    <button
                      key={identity.tenantId}
                      onClick={() => {
                        setPickerOpen(false);
                        onSwitchIdentity(identity.tenantId, identity.userId);
                      }}
                      className={clsx(
                        "flex w-full items-center justify-between rounded-lg px-2 py-2 text-left text-label transition-colors",
                        profile?.tenantId === identity.tenantId
                          ? "bg-accent-soft font-medium text-accent-ink"
                          : "hover:bg-surface-2",
                      )}
                    >
                      {identity.label}
                    </button>
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </>
      )}
    </div>
  );

  return (
    <>
      <aside className="relative z-10 hidden h-full md:block">{body}</aside>

      <AnimatePresence>
        {open && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 z-40 md:hidden"
              style={{ background: "var(--scrim)" }}
              onClick={onClose}
              aria-hidden
            />
            <motion.aside
              ref={drawerRef}
              role="dialog"
              aria-modal="true"
              aria-label="Conversations"
              initial={reduceMotion ? false : { x: -300 }}
              animate={{ x: 0 }}
              exit={{ x: -300 }}
              transition={{ type: "spring", stiffness: 380, damping: 34 }}
              className="fixed inset-y-0 left-0 z-50 bg-background md:hidden"
            >
              <div className="relative h-full">
                {body}
                <button
                  aria-label="Close menu"
                  onClick={onClose}
                  className="absolute right-2 top-2 grid h-9 w-9 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-foreground"
                >
                  <X size={17} />
                </button>
              </div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </>
  );
}
