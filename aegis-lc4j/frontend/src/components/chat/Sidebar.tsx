"use client";

import { AnimatePresence, motion } from "framer-motion";
import {
  ArrowUpRight,
  ChevronDown,
  Landmark,
  LayoutDashboard,
  MessageSquarePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Trash2,
  X,
} from "lucide-react";
import Link from "next/link";
import clsx from "clsx";
import { useMemo, useState } from "react";
import type { Conversation } from "@/hooks/useConversations";
import { DEMO_IDENTITIES } from "@/hooks/useSession";
import { displayName } from "@/lib/tenantNames";
import type { Profile } from "@/lib/api";

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
    const key = day >= today ? "Today" : day >= yesterday ? "Yesterday" : day >= weekAgo ? "Previous 7 days" : "Older";
    buckets.get(key)!.push(c);
  }
  return order
    .map((k): [string, Conversation[]] => [k, buckets.get(k)!])
    .filter(([, list]) => list.length > 0);
}

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

  const filtered = useMemo(
    () =>
      query.trim()
        ? conversations.filter((c) => c.title.toLowerCase().includes(query.trim().toLowerCase()))
        : conversations,
    [conversations, query],
  );
  const grouped = useMemo(() => groupByDate(filtered), [filtered]);

  const body = (
    <div
      className={clsx(
        "flex h-full flex-col gap-3 p-3 transition-[width] duration-200",
        collapsed ? "w-[68px]" : "w-72",
      )}
      style={{ background: "linear-gradient(160deg, var(--wash-a), var(--wash-b))" }}
    >
      {}
      <div className="flex items-center justify-between px-1 py-1">
        {!collapsed && (
          <span className="flex items-center gap-1.5 text-[15px] font-bold tracking-tight">
            <Landmark size={16} className="text-accent" /> ACHU
          </span>
        )}
        <button
          onClick={() => setCollapsed((c) => !c)}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="ml-auto hidden rounded-lg p-1.5 text-muted hover:bg-pill md:block"
        >
          {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
        </button>
      </div>

      {}
      {!collapsed && (
        <div className="relative">
          <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search"
            className="w-full rounded-full border border-pill-border bg-pill py-2.5 pl-10 pr-3 text-sm outline-none backdrop-blur-sm placeholder:text-muted focus:border-accent/40 transition-colors"
          />
        </div>
      )}

      {}
      <div className="flex flex-col gap-1.5">
        <button
          onClick={onCreate}
          className={clsx(
            "flex items-center gap-3 rounded-full border border-pill-border bg-pill py-2.5 backdrop-blur-sm hover:bg-pill/80 transition-colors",
            collapsed ? "justify-center px-2.5" : "px-4",
          )}
        >
          <MessageSquarePlus size={16} className="shrink-0 text-accent" />
          {!collapsed && <span className="text-sm font-medium">New chat</span>}
        </button>
        <Link
          href="/admin"
          className={clsx(
            "flex items-center gap-3 rounded-full border border-pill-border bg-pill py-2.5 backdrop-blur-sm hover:bg-pill/80 transition-colors",
            collapsed ? "justify-center px-2.5" : "px-4",
          )}
        >
          <LayoutDashboard size={16} className="shrink-0 text-system" />
          {!collapsed && <span className="text-sm font-medium">Admin</span>}
        </Link>
      </div>

      {!collapsed && (
        <>
          <div className="mt-1 flex items-center justify-between px-1">
            <p className="text-xs font-semibold text-foreground/80">Chat history</p>
          </div>

          <div className="flex-1 overflow-y-auto">
            {grouped.map(([label, list]) => (
              <div key={label} className="mb-2">
                <p className="px-2 py-1 text-[10px] font-semibold uppercase tracking-wide text-muted">
                  {label}
                </p>
                <AnimatePresence initial={false}>
                  {list.map((c) => (
                    <motion.div
                      key={c.id}
                      layout
                      initial={{ opacity: 0, x: -12 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, x: -12 }}
                      className={clsx(
                        "group flex items-center gap-2 rounded-full px-3 py-2 text-sm cursor-pointer mb-0.5",
                        c.id === activeId ? "bg-pill font-medium" : "hover:bg-pill/60",
                      )}
                      onClick={() => {
                        onSelect(c.id);
                        onClose();
                      }}
                    >
                      <ArrowUpRight size={13} className="shrink-0 text-muted" />
                      <span className="flex-1 truncate">{c.title}</span>
                      <button
                        aria-label="Delete conversation"
                        className="opacity-0 group-hover:opacity-100 text-muted hover:text-critical transition-opacity"
                        onClick={(e) => {
                          e.stopPropagation();
                          if (window.confirm(`Delete "${c.title}"? This can't be undone.`)) {
                            onRemove(c.id);
                          }
                        }}
                      >
                        <Trash2 size={13} />
                      </button>
                    </motion.div>
                  ))}
                </AnimatePresence>
              </div>
            ))}
            {filtered.length === 0 && (
              <p className="px-3 py-2 text-xs text-muted">
                {query ? "No matching chats." : "No conversations yet."}
              </p>
            )}
          </div>
        </>
      )}

      {!collapsed && (
        <div className="relative">
          <button
            onClick={() => setPickerOpen((o) => !o)}
            className="flex w-full items-center justify-between gap-2 rounded-full border border-pill-border bg-pill px-3 py-2 text-left backdrop-blur-sm hover:bg-pill/80 transition-colors"
          >
            <span className="min-w-0 truncate text-[11px] text-muted">
              {profile ? (
                <>
                  <span className="font-medium text-foreground">{profile.userId}</span>
                  {" · "}
                  {displayName(profile.tenantId)}
                </>
              ) : (
                "Signing in…"
              )}
            </span>
            <ChevronDown size={13} className={clsx("shrink-0 text-muted transition-transform", pickerOpen && "rotate-180")} />
          </button>
          <AnimatePresence>
            {pickerOpen && (
              <motion.div
                initial={{ opacity: 0, y: 6, scale: 0.97 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 6, scale: 0.97 }}
                transition={{ type: "spring", stiffness: 450, damping: 30 }}
                className="absolute bottom-full left-0 z-10 mb-2 w-full rounded-xl border border-border-soft bg-surface p-1.5 shadow-lg"
              >
                <p className="px-2 py-1 text-[10px] font-medium uppercase tracking-wide text-muted">
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
                      "flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left text-xs transition-colors",
                      profile?.tenantId === identity.tenantId
                        ? "bg-accent-soft text-accent font-medium"
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
      )}
    </div>
  );

  return (
    <>
      {}
      <aside className="hidden md:block h-full">{body}</aside>
      {}
      <AnimatePresence>
        {open && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 z-40 bg-black/40 md:hidden"
              onClick={onClose}
            />
            <motion.aside
              initial={{ x: -280 }}
              animate={{ x: 0 }}
              exit={{ x: -280 }}
              transition={{ type: "spring", stiffness: 380, damping: 34 }}
              className="fixed inset-y-0 left-0 z-50 md:hidden"
            >
              <div className="relative h-full">
                {body}
                <button
                  aria-label="Close menu"
                  onClick={onClose}
                  className="absolute right-2 top-2 text-muted"
                >
                  <X size={18} />
                </button>
              </div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </>
  );
}
