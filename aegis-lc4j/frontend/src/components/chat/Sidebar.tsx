"use client";

import { AnimatePresence, motion } from "framer-motion";
import { MessageSquarePlus, Trash2, X } from "lucide-react";
import clsx from "clsx";
import type { Conversation } from "@/hooks/useConversations";

export function Sidebar({
  open,
  onClose,
  conversations,
  activeId,
  onSelect,
  onCreate,
  onRemove,
}: {
  open: boolean;
  onClose: () => void;
  conversations: Conversation[];
  activeId: string;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onRemove: (id: string) => void;
}) {
  const body = (
    <div className="flex h-full w-64 flex-col gap-2 border-r border-border-soft bg-surface p-3">
      <button
        onClick={onCreate}
        className="flex items-center gap-2 rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium hover:opacity-90 transition-opacity"
      >
        <MessageSquarePlus size={16} /> New chat
      </button>
      <div className="mt-1 flex-1 overflow-y-auto">
        <AnimatePresence initial={false}>
          {conversations.map((c) => (
            <motion.div
              key={c.id}
              layout
              initial={{ opacity: 0, x: -12 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -12 }}
              className={clsx(
                "group flex items-center justify-between rounded-lg px-3 py-2 text-sm cursor-pointer mb-0.5",
                c.id === activeId ? "bg-accent-soft text-accent font-medium" : "hover:bg-surface-2",
              )}
              onClick={() => {
                onSelect(c.id);
                onClose();
              }}
            >
              <span className="truncate">{c.title}</span>
              <button
                aria-label="Delete conversation"
                className="opacity-0 group-hover:opacity-100 text-muted hover:text-red-500 transition-opacity"
                onClick={(e) => {
                  e.stopPropagation();
                  onRemove(c.id);
                }}
              >
                <Trash2 size={14} />
              </button>
            </motion.div>
          ))}
        </AnimatePresence>
        {conversations.length === 0 && (
          <p className="px-3 py-2 text-xs text-muted">No conversations yet.</p>
        )}
      </div>
      <p className="px-1 text-[11px] text-muted">Signed in as demo-user · achu-bank</p>
    </div>
  );

  return (
    <>
      {/* Desktop: static */}
      <aside className="hidden md:block h-full">{body}</aside>
      {/* Mobile: slide-over drawer */}
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
