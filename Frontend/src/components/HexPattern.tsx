export function HexPattern({ opacity = 0.035 }: { opacity?: number }) {
  return (
    <svg
      aria-hidden
      className="absolute inset-0 w-full h-full pointer-events-none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <pattern id="honey" x="0" y="0" width="60" height="104" patternUnits="userSpaceOnUse">
          <g fill="none" stroke="var(--gold)" strokeWidth="1.1" strokeLinejoin="round">
            <polygon points="30,2 58,18 58,50 30,66 2,50 2,18" />
            <polygon points="60,54 88,70 88,102 60,118 32,102 32,70" />
            <polygon points="0,54 28,70 28,102 0,118 -28,102 -28,70" />
          </g>
        </pattern>
        <radialGradient id="fade" cx="50%" cy="40%" r="80%">
          <stop offset="0%" stopColor="#fff" stopOpacity="1" />
          <stop offset="55%" stopColor="#fff" stopOpacity="0.55" />
          <stop offset="100%" stopColor="#fff" stopOpacity="0" />
        </radialGradient>
        <mask id="fadeMask">
          <rect width="100%" height="100%" fill="url(#fade)" />
        </mask>
      </defs>
      <rect width="100%" height="100%" fill="url(#honey)" opacity={opacity} mask="url(#fadeMask)" />
    </svg>
  );
}
