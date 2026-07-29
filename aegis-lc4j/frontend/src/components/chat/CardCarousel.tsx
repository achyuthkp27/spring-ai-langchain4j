"use client";

import { memo, useRef, useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import { ChevronLeft, ChevronRight, Snowflake, Wifi } from "lucide-react";
import type { CardData } from "@/lib/sse";

const NETWORK_STYLE: Record<CardData["network"], { gradient: string; ring: string }> = {
  VISA: { gradient: "from-[#141a44] via-[#1e2a6b] to-[#3d4fd6]", ring: "ring-[#8b9cff]/25" },
  MASTERCARD: { gradient: "from-[#20103f] via-[#341a63] to-[#6d3ec4]", ring: "ring-[#a78bfa]/25" },
};

function CardFace({ card, index, total }: { card: CardData; index: number; total: number }) {
  const style = NETWORK_STYLE[card.network] ?? NETWORK_STYLE.VISA;
  const frozen = card.status === "FROZEN";
  const reduceMotion = useReducedMotion();

  return (
    <motion.div
      role="group"
      aria-roledescription="card"
      aria-label={`${card.network} ${card.type.toLowerCase()} card ending ${card.last4}, ${
        frozen ? "frozen" : "active"
      }. ${index + 1} of ${total}.`}
      initial={reduceMotion ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: reduceMotion ? 0 : index * 0.06, duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      whileHover={reduceMotion ? undefined : { y: -3 }}
      className={clsx(
        "relative aspect-[1.586/1] w-60 shrink-0 snap-center overflow-hidden rounded-2xl p-4 text-white",
        "bg-gradient-to-br shadow-[0_20px_44px_-24px_rgba(0,0,0,0.85)] ring-1",
        style.gradient,
        style.ring,
        frozen && "saturate-[0.2] brightness-[0.7]",
      )}
    >
      {/* Sheen sweeps on hover rather than once on mount, where it was almost
          always missed behind the streaming text above. */}
      {!reduceMotion && (
        <motion.div
          aria-hidden
          className="pointer-events-none absolute inset-0 bg-gradient-to-r from-transparent via-white/12 to-transparent"
          initial={{ x: "-120%" }}
          whileHover={{ x: "120%" }}
          transition={{ duration: 0.9, ease: "easeInOut" }}
        />
      )}

      <div className="flex items-start justify-between">
        <span className="text-[10px] font-medium uppercase tracking-[0.14em] text-white/70">
          {card.type}
        </span>
        <Wifi size={16} className="rotate-90 text-white/70" aria-hidden />
      </div>

      <div
        className="mt-5 h-6 w-8 rounded-md bg-gradient-to-br from-[#f6e27a] to-[#c9a227]"
        aria-hidden
      />

      <p className="mt-4 font-mono text-[15px] tracking-[0.16em] text-white/95">
        •••• •••• •••• {card.last4}
      </p>

      <div className="mt-3 flex items-end justify-between">
        <span className="text-[10px] tracking-wide text-white/55">{card.cardId}</span>
        <span className="text-sm font-semibold italic tracking-tight text-white/95">
          {card.network === "VISA" ? "VISA" : "Mastercard"}
        </span>
      </div>

      {frozen && (
        <div className="absolute inset-0 flex items-center justify-center gap-1.5 bg-black/40 backdrop-blur-[1px]">
          <Snowflake size={14} className="text-white" aria-hidden />
          <span className="text-micro font-semibold uppercase tracking-[0.14em] text-white">
            Frozen
          </span>
        </div>
      )}
    </motion.div>
  );
}

export const CardCarousel = memo(function CardCarousel({ cards }: { cards: CardData[] }) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);
  const reduceMotion = useReducedMotion();

  const scrollToIndex = (i: number) => {
    const track = trackRef.current;
    if (!track) return;
    const clamped = Math.max(0, Math.min(cards.length - 1, i));
    track.children[clamped]?.scrollIntoView({
      behavior: reduceMotion ? "auto" : "smooth",
      inline: "center",
      block: "nearest",
    });
    setActive(clamped);
  };

  const handleScroll = () => {
    const track = trackRef.current;
    if (!track) return;
    const center = track.scrollLeft + track.clientWidth / 2;
    let closest = 0;
    let closestDist = Infinity;
    Array.from(track.children).forEach((child, i) => {
      const el = child as HTMLElement;
      const dist = Math.abs(el.offsetLeft + el.clientWidth / 2 - center);
      if (dist < closestDist) {
        closestDist = dist;
        closest = i;
      }
    });
    setActive(closest);
  };

  if (cards.length === 0) return null;

  const arrow =
    "grid h-11 w-11 place-items-center rounded-full border border-hairline bg-surface-2/80 text-muted backdrop-blur transition-colors hover:text-foreground disabled:opacity-30";

  return (
    <div className="relative w-full">
      <div
        ref={trackRef}
        onScroll={handleScroll}
        aria-roledescription="carousel"
        aria-label={`${cards.length} card${cards.length > 1 ? "s" : ""}`}
        className="scroll-none flex snap-x snap-mandatory gap-3 overflow-x-auto pb-2"
      >
        {cards.map((c, i) => (
          <CardFace key={c.cardId} card={c} index={i} total={cards.length} />
        ))}
      </div>

      {cards.length > 1 && (
        <>
          <div className="mt-1 flex items-center justify-between gap-2">
            <button
              type="button"
              aria-label="Previous card"
              disabled={active === 0}
              onClick={() => scrollToIndex(active - 1)}
              className={arrow}
            >
              <ChevronLeft size={16} />
            </button>

            <div className="flex items-center gap-1.5" role="tablist" aria-label="Select card">
              {cards.map((c, i) => (
                <button
                  key={c.cardId}
                  type="button"
                  role="tab"
                  aria-selected={i === active}
                  aria-label={`Card ${i + 1} of ${cards.length}`}
                  onClick={() => scrollToIndex(i)}
                  className="grid h-9 w-6 place-items-center"
                >
                  <span
                    className={clsx(
                      "block h-1.5 rounded-full transition-all",
                      i === active ? "w-5 bg-accent" : "w-1.5 bg-muted/40",
                    )}
                  />
                </button>
              ))}
            </div>

            <button
              type="button"
              aria-label="Next card"
              disabled={active === cards.length - 1}
              onClick={() => scrollToIndex(active + 1)}
              className={arrow}
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </>
      )}
    </div>
  );
});
