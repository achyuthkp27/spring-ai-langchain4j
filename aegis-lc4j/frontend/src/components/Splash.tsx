"use client";

import { useCallback, useEffect, useState } from "react";
import clsx from "clsx";
import { BrandBeacon } from "@/components/BrandMark";

const SEEN_KEY = "aegis.splash.seen";
const HOLD_MS = 1900;
const FADE_MS = 420;

/**
 * Startup splash. Covers the first paint while the app mints its token and
 * loads history behind it, so the hold is doing real work rather than being a
 * pure delay.
 *
 * Shown once per tab session — navigating to /admin and back does not replay
 * it. Skippable with any click or key, and collapsed to a brief hold under
 * prefers-reduced-motion.
 */
export function Splash() {
  const [phase, setPhase] = useState<"hidden" | "showing" | "leaving">("hidden");

  useEffect(() => {
    let seen = false;
    try {
      seen = sessionStorage.getItem(SEEN_KEY) === "1";
    } catch {
      seen = false;
    }
    if (seen) return;

    try {
      sessionStorage.setItem(SEEN_KEY, "1");
    } catch {
      /* private mode — splash simply replays next load */
    }

    const reduced =
      typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
    const hold = reduced ? 500 : HOLD_MS;

    setPhase("showing");
    const leave = setTimeout(() => setPhase("leaving"), hold);
    const gone = setTimeout(() => setPhase("hidden"), hold + FADE_MS);
    return () => {
      clearTimeout(leave);
      clearTimeout(gone);
    };
  }, []);

  const skip = useCallback(() => {
    setPhase((p) => (p === "showing" ? "leaving" : p));
    setTimeout(() => setPhase("hidden"), FADE_MS);
  }, []);

  useEffect(() => {
    if (phase !== "showing") return;
    const onKey = () => skip();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [phase, skip]);

  if (phase === "hidden") return null;

  return (
    <div
      role="status"
      aria-label="Loading Achu FinBot"
      onClick={skip}
      className={clsx(
        "fixed inset-0 z-[100] grid place-items-center bg-background transition-opacity duration-[420ms] ease-out",
        phase === "leaving" ? "pointer-events-none opacity-0" : "opacity-100",
      )}
    >
      <div className="canvas-bloom" />
      <div className="canvas-grain" />

      <div className="relative z-10 flex w-full max-w-md flex-col items-center px-6">
        <BrandBeacon size={84} animate gradientId="splash-beacon" />

        <h1
          className="anim-rise mt-2 text-2xl font-semibold tracking-tight"
          style={{ animationDelay: "340ms" }}
        >
          Achu FinBot
        </h1>
        <p
          className="anim-rise mt-2 text-label text-muted"
          style={{ animationDelay: "500ms" }}
        >
          Secure banking intelligence
        </p>

        <div
          className="anim-rise mt-9 h-px w-40 overflow-hidden bg-hairline"
          style={{ animationDelay: "640ms" }}
          aria-hidden
        >
          <div className="anim-sweep h-full w-1/3 bg-accent" />
        </div>

        <p
          className="anim-rise mt-6 text-micro text-muted/70"
          style={{ animationDelay: "800ms" }}
        >
          Press any key to skip
        </p>
      </div>
    </div>
  );
}
