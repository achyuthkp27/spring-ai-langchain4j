"use client";

import { useCallback, useEffect, useState } from "react";
import { deleteConversation } from "@/lib/api";

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
}

function storageKey(identityKey: string | null): string {
  return `aegis.conversations.${identityKey ?? "unknown"}`;
}

function load(identityKey: string | null): Conversation[] {
  try {
    return JSON.parse(localStorage.getItem(storageKey(identityKey)) ?? "[]") as Conversation[];
  } catch {
    return [];
  }
}

export function useConversations(identityKey: string | null) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string>("default");

  useEffect(() => {
    if (!identityKey) return;
    const list = load(identityKey);
    setConversations(list);
    if (list.length) setActiveId(list[0].id);
  }, [identityKey]);

  const persist = useCallback(
    (list: Conversation[]) => {
      setConversations(list);
      if (identityKey) localStorage.setItem(storageKey(identityKey), JSON.stringify(list));
    },
    [identityKey],
  );

  const create = useCallback(() => {
    const conv: Conversation = {
      id: `c-${Date.now().toString(36)}`,
      title: "New chat",
      createdAt: Date.now(),
    };
    persist([conv, ...load(identityKey)]);
    setActiveId(conv.id);
    return conv.id;
  }, [identityKey, persist]);

  const titleFrom = useCallback(
    (convId: string, text: string) => {
      const list = load(identityKey);
      const idx = list.findIndex((c) => c.id === convId);
      const title = text.length > 34 ? text.slice(0, 34) + "…" : text;
      if (idx >= 0) {
        if (list[idx].title === "New chat") {
          list[idx] = { ...list[idx], title };
          persist(list);
        }
      } else {
        persist([{ id: convId, title, createdAt: Date.now() }, ...list]);
      }
    },
    [identityKey, persist],
  );

  const remove = useCallback(
    (convId: string) => {
      const list = load(identityKey).filter((c) => c.id !== convId);
      persist(list);
      if (activeId === convId) setActiveId(list[0]?.id ?? "default");
      void deleteConversation(convId).catch(() => {});
    },
    [activeId, identityKey, persist],
  );

  return { conversations, activeId, setActiveId, create, titleFrom, remove };
}
