import { useEffect, useRef } from "react";

/**
 * Full-bleed background photo with a subtle scroll-linked parallax drift and
 * a dark scrim on top so text stays readable. Used to drop the cinematic
 * hive photos behind key sections without needing any extra libraries.
 */
export function ParallaxImage({
  src,
  strength = 36,
  scrimClassName = "bg-gradient-to-b from-background/75 via-background/88 to-background",
  imageClassName = "",
}: {
  src: string;
  strength?: number;
  scrimClassName?: string;
  imageClassName?: string;
}) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const wrap = wrapRef.current;
    const img = imgRef.current;
    if (!wrap || !img) return;

    let raf = 0;
    const update = () => {
      const rect = wrap.getBoundingClientRect();
      const vh = window.innerHeight || 1;
      // -1 when section top is at viewport bottom, +1 when section bottom is at viewport top
      const progress = (rect.top / vh) * -1;
      const offset = Math.max(-1, Math.min(1, progress)) * strength;
      img.style.transform = `translate3d(0, ${offset}px, 0) scale(1.12)`;
    };
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(update);
    };
    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
    };
  }, [strength]);

  return (
    <div ref={wrapRef} className="absolute inset-0 overflow-hidden" aria-hidden>
      <div ref={imgRef} className="absolute inset-0 will-change-transform">
        <img src={src} alt="" className={`w-full h-full object-cover ${imageClassName}`} />
      </div>
      <div className={`absolute inset-0 ${scrimClassName}`} />
    </div>
  );
}
