"use client";

import { useCallback, useRef, useState } from "react";
import { getToken, fetchHistory } from "@/lib/api";
import { streamChat, type ChatMeta } from "@/lib/sse";

export interface Message {
  id: string;
  role: "user" | "assistant";
  text: string;
  streaming?: boolean;
  meta?: ChatMeta;
}

export interface ChatState {
  messages: Message[];
  statuses: string[]; // live tool-progress chips for the in-flight turn
  busy: boolean;
}

let nextId = 0;
const id = () => `m${++nextId}`;

export function useChatStream(conversationId: string) {
  const [state, setState] = useState<ChatState>({ messages: [], statuses: [], busy: false });
  const abortRef = useRef<AbortController | null>(null);

  const loadHistory = useCallback(async () => {
    const history = await fetchHistory(conversationId);
    setState({
      messages: history.map((h) => ({ id: id(), role: h.role, text: h.text })),
      statuses: [],
      busy: false,
    });
  }, [conversationId]);

  const send = useCallback(
    async (text: string) => {
      const userMsg: Message = { id: id(), role: "user", text };
      const botId = id();
      setState((s) => ({
        messages: [...s.messages, userMsg, { id: botId, role: "assistant", text: "", streaming: true }],
        statuses: [],
        busy: true,
      }));

      const controller = new AbortController();
      abortRef.current = controller;
      const token = await getToken();

      const patchBot = (fn: (m: Message) => Message) =>
        setState((s) => ({
          ...s,
          messages: s.messages.map((m) => (m.id === botId ? fn(m) : m)),
        }));

      await streamChat(
        token,
        conversationId,
        text,
        {
          onToken: (t) => patchBot((m) => ({ ...m, text: m.text + t })),
          onStatus: (status) =>
            setState((s) => ({ ...s, statuses: [...s.statuses.slice(-3), status] })),
          onMeta: (meta) => {
            patchBot((m) => ({ ...m, streaming: false, meta }));
            setState((s) => ({ ...s, statuses: [], busy: false }));
          },
          onError: () => {
            patchBot((m) => ({
              ...m,
              streaming: false,
              text: m.text || "Something went wrong reaching the assistant. Please try again.",
            }));
            setState((s) => ({ ...s, statuses: [], busy: false }));
          },
        },
        controller.signal,
      );
    },
    [conversationId],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  return { ...state, send, stop, loadHistory };
}
