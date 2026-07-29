"use client";

/**
 * Replaces the root layout when the layout itself throws, so it cannot rely on
 * app CSS variables — styles are inlined and it renders its own <html>/<body>.
 */
export default function GlobalError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang="en">
      <body
        style={{
          margin: 0,
          minHeight: "100dvh",
          display: "grid",
          placeItems: "center",
          background: "#08090c",
          color: "#e9ecf5",
          fontFamily: "system-ui, sans-serif",
        }}
      >
        <div style={{ maxWidth: 360, padding: 24, textAlign: "center" }}>
          <h1 style={{ fontSize: 16, fontWeight: 600, margin: "0 0 8px" }}>Something went wrong</h1>
          <p style={{ fontSize: 13, lineHeight: 1.5, color: "#97a0b5", margin: "0 0 20px" }}>
            The app failed to load. Please refresh, or try again.
          </p>
          <button
            onClick={reset}
            style={{
              border: "none",
              borderRadius: 12,
              padding: "8px 16px",
              fontSize: 13,
              fontWeight: 500,
              color: "#08090c",
              background: "#8b9cff",
              cursor: "pointer",
            }}
          >
            Try again
          </button>
        </div>
      </body>
    </html>
  );
}
