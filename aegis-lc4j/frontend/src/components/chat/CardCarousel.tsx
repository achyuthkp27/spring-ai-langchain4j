"use client";

import { useRef, useState } from "react";
import { motion } from "framer-motion";
import clsx from "clsx";
import { ChevronLeft, ChevronRight, Snowflake, Wifi } from "lucide-react";
import type { CardData } from "@/lib/sse";

const NETWORK_STYLE: Record<CardData["network"], { gradient: string; ring: string }> = {
  VISA: { gradient: "from-[#1a2980] via-[#26428a] to-[#2563eb]", ring: "ring-blue-400/30" },
  MASTERCARD: { gradient: "from-[#2b1055] via-[#3a1c6e] to-[#4c1d95]", ring: "ring-violet-400/30" },
};

function CardFace({ card, index }: { card: CardData; index: number }) {
  const style = NETWORK_STYLE[card.network] ?? NETWORK_STYLE.VISA;
  const frozen = card.status === "FROZEN";

  return (
    <motion.div
      initial={{ opacity: 0, y: 16, rotateY: -12 }}
      animate={{ opacity: 1, y: 0, rotateY: 0 }}
      transition={{ delay: index * 0.08, type: "spring", stiffness: 320, damping: 28 }}
      whileHover={{ y: -3 }}
      className={clsx(
        "relative aspect-[1.586/1] w-64 shrink-0 snap-center overflow-hidden rounded-2xl p-4 text-white",
        "bg-gradient-to-br shadow-lg ring-1",
        style.gradient,
        style.ring,
        frozen && "saturate-[0.25] brightness-[0.75]",
      )}
      style={{ perspective: 800 }}
    >
      {}
      <motion.div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-gradient-to-r from-transparent via-white/10 to-transparent"
        initial={{ x: "-120%" }}
        animate={{ x: "120%" }}
        transition={{ delay: 0.3 + index * 0.08, duration: 1.1, ease: "easeInOut" }}
      />

      <div className="flex items-start justify-between">
        <span className="text-[10px] font-medium uppercase tracking-wider text-white/70">
          {card.type}
        </span>
        <Wifi size={16} className="rotate-90 text-white/70" />
      </div>

      {}
      <div className="mt-5 h-6 w-8 rounded-md bg-gradient-to-br from-yellow-200 to-yellow-500/80" />

      <p className="mt-4 font-mono text-[15px] tracking-[0.15em] text-white/95">
        •••• •••• •••• {card.last4}
      </p>

      <div className="mt-3 flex items-end justify-between">
        <span className="text-[9px] text-white/60">{card.cardId}</span>
        <span className="font-serif text-base italic tracking-tight text-white/95">
          {card.network === "VISA" ? "VISA" : "Mastercard"}
        </span>
      </div>

      {frozen && (
        <div className="absolute inset-0 flex items-center justify-center gap-1.5 bg-black/30 backdrop-blur-[1px]">
          <Snowflake size={14} className="text-white" />
          <span className="text-xs font-semibold uppercase tracking-wide text-white">Frozen</span>
        </div>
      )}
    </motion.div>
  );
}

export function CardCarousel({ cards }: { cards: CardData[] }) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);

  const scrollToIndex = (i: number) => {
    const track = trackRef.current;
    if (!track) return;
    const clamped = Math.max(0, Math.min(cards.length - 1, i));
    track.children[clamped]?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
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

  return (
    <div className="relative mt-1 w-full">
      <div
        ref={trackRef}
        onScroll={handleScroll}
        className="flex snap-x snap-mandatory gap-3 overflow-x-auto pb-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {cards.map((c, i) => (
          <CardFace key={c.cardId} card={c} index={i} />
        ))}
      </div>

      {cards.length > 1 && (
        <>
          <div className="mt-1 flex items-center justify-center gap-1.5">
            {cards.map((c, i) => (
              <button
                key={c.cardId}
                aria-label={`Show card ${i + 1}`}
                onClick={() => scrollToIndex(i)}
                className={clsx(
                  "h-1.5 rounded-full transition-all",
                  i === active ? "w-4 bg-accent" : "w-1.5 bg-muted/40",
                )}
              />
            ))}
          </div>
          <button
            aria-label="Previous card"
            onClick={() => scrollToIndex(active - 1)}
            className="absolute left-0 top-1/2 -translate-x-3 -translate-y-1/2 grid h-7 w-7 place-items-center rounded-full border border-border-soft bg-surface text-muted shadow-sm hover:text-foreground transition-colors"
          >
            <ChevronLeft size={14} />
          </button>
          <button
            aria-label="Next card"
            onClick={() => scrollToIndex(active + 1)}
            className="absolute right-0 top-1/2 translate-x-3 -translate-y-1/2 grid h-7 w-7 place-items-center rounded-full border border-border-soft bg-surface text-muted shadow-sm hover:text-foreground transition-colors"
          >
            <ChevronRight size={14} />
          </button>
        </>
      )}
    </div>
  );
}
