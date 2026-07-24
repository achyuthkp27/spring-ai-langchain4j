"use client";

import { useCallback, useEffect, useState } from "react";

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
}

const KEY = "aegis.conversations";

function load(): Conversation[] {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "[]") as Conversation[];
  } catch {
    return [];
  }
}

export function useConversations() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string>("default");

  useEffect(() => {
    const list = load();
    setConversations(list);
    if (list.length) setActiveId(list[0].id);
  }, []);

  const persist = (list: Conversation[]) => {
    setConversations(list);
    localStorage.setItem(KEY, JSON.stringify(list));
  };

  const create = useCallback(() => {
    const conv: Conversation = {
      id: `c-${Date.now().toString(36)}`,
      title: "New chat",
      createdAt: Date.now(),
    };
    persist([conv, ...load()]);
    setActiveId(conv.id);
    return conv.id;
  }, []);

  /** First user message becomes the sidebar title. */
  const titleFrom = useCallback((convId: string, text: string) => {
    const list = load();
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
  }, []);

  const remove = useCallback(
    (convId: string) => {
      const list = load().filter((c) => c.id !== convId);
      persist(list);
      if (activeId === convId) setActiveId(list[0]?.id ?? "default");
    },
    [activeId],
  );

  return { conversations, activeId, setActiveId, create, titleFrom, remove };
}
