import { BrandBeacon } from "@/components/BrandMark";

export default function Loading() {
  return (
    <div className="relative grid min-h-dvh place-items-center">
      <div className="canvas-bloom" aria-hidden />
      <div className="relative z-10 flex flex-col items-center">
        <BrandBeacon size={64} gradientId="loading-beacon" />
        <div className="mt-6 h-px w-32 overflow-hidden bg-hairline" aria-hidden>
          <div className="anim-sweep h-full w-1/3 bg-accent" />
        </div>
      </div>
    </div>
  );
}
