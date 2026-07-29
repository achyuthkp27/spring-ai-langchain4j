import clsx from "clsx";

const STAR =
  "M12 0.6 C13.15 6.45 17.55 10.85 23.4 12 C17.55 13.15 13.15 17.55 12 23.4 C10.85 17.55 6.45 13.15 0.6 12 C6.45 10.85 10.85 6.45 12 0.6 Z";

/**
 * The four-point star used as the app's mark: splash, hero, and the assistant
 * avatar. `gradientId` is parameterised because several instances can be on
 * screen at once and duplicate SVG ids would make them share one gradient.
 */
export function BrandMark({
  size = 24,
  glow = false,
  className,
  gradientId = "brand-mark",
}: {
  size?: number;
  glow?: boolean;
  className?: string;
  gradientId?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden
      className={clsx(glow && "mark-glow", className)}
    >
      <defs>
        <linearGradient id={gradientId} x1="12" y1="0" x2="12" y2="24" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="var(--foreground)" stopOpacity="0.95" />
          <stop offset="45%" stopColor="var(--accent)" />
          <stop offset="100%" stopColor="var(--system)" />
        </linearGradient>
      </defs>
      <path d={STAR} fill={`url(#${gradientId})`} />
    </svg>
  );
}

/**
 * The mark with the horizontal lens streak running through it — the hero and
 * splash treatment. `size` is the mark; the streak spans the full width of the
 * container, so give this a wide parent.
 */
export function BrandBeacon({
  size = 72,
  animate = false,
  gradientId = "brand-beacon",
}: {
  size?: number;
  animate?: boolean;
  gradientId?: string;
}) {
  return (
    <div className="relative flex w-full items-center justify-center" style={{ height: size * 1.6 }}>
      <div
        className={clsx(
          "mark-streak absolute left-0 right-0 top-1/2 h-px -translate-y-1/2",
          animate && "anim-streak",
        )}
        aria-hidden
      />
      <div
        className={clsx("relative", animate && "anim-ignite")}
        style={{ filter: "drop-shadow(0 0 26px var(--glow-a)) drop-shadow(0 0 70px var(--glow-b))" }}
      >
        <BrandMark size={size} gradientId={gradientId} />
      </div>
    </div>
  );
}
