import { Link } from "@tanstack/react-router";
import logoMark from "@/assets/logo-mark.png";

const SIZES = {
  sm: { mark: "w-10 h-10", text: "text-xl" },
  md: { mark: "w-12 h-12", text: "text-2xl" },
  lg: { mark: "w-16 h-16", text: "text-3xl" },
} as const;

export function Logo({
  size = "md",
  withLink = true,
  className = "",
}: {
  size?: keyof typeof SIZES;
  withLink?: boolean;
  className?: string;
}) {
  const s = SIZES[size];
  const content = (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <img src={logoMark} alt="EzzyHub" className={`${s.mark} shrink-0 object-contain`} />
      <span className={`${s.text} font-medium tracking-tight font-display leading-none`}>
        Ezzy<span className="text-[var(--gold)]">Hub</span>
      </span>
    </span>
  );

  if (!withLink) return content;

  return (
    <Link to="/" className="inline-flex items-center text-foreground">
      {content}
    </Link>
  );
}
