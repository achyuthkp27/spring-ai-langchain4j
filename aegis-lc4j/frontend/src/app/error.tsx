"use client";

import { useEffect } from "react";
import { AlertTriangle, RotateCcw } from "lucide-react";

export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error("app.error_boundary", error);
  }, [error]);

  return (
    <div className="relative grid min-h-dvh place-items-center px-6">
      <div className="canvas-bloom" aria-hidden />
      <div className="canvas-grain" aria-hidden />
      <div className="panel lit relative z-10 w-full max-w-sm p-6 text-center">
        <span className="mx-auto grid h-11 w-11 place-items-center rounded-full bg-warning-soft text-warning-ink">
          <AlertTriangle size={20} />
        </span>
        <h1 className="mt-4 text-label font-semibold">Something went wrong</h1>
        <p className="mt-1.5 text-micro leading-relaxed text-muted">
          The app hit an unexpected error. Your data is safe — try again, and if it keeps happening,
          refresh the page.
        </p>
        <button
          onClick={reset}
          className="mt-5 inline-flex items-center gap-2 rounded-xl bg-accent-strong px-4 py-2 text-label font-medium text-on-accent transition-opacity hover:opacity-90"
        >
          <RotateCcw size={14} /> Try again
        </button>
      </div>
    </div>
  );
}
