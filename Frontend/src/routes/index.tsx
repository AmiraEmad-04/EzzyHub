import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  ArrowDown,
  FileCode2,
  ScanSearch,
  Sparkles,
  Play,
  Pause,
  VolumeX,
  Volume2,
  Upload,
  Code2,
  GitCompareArrows,
  Radar,
  FileCheck2,
  Bug,
  ShieldAlert,
  Copy,
  Percent,
  ShieldCheck,
} from "lucide-react";
import storyVideo from "@/assets/ezzyhub-story.mp4";
import heroBg from "@/assets/backgrounds/hero-bg.jpg";
import honeyDropBg from "@/assets/backgrounds/honey-drop-bg.jpg";
import hexGridBg from "@/assets/backgrounds/hex-grid-bg.jpg";
import mixedCellsBg from "@/assets/backgrounds/mixed-cells-bg.jpg";
import { Logo } from "@/components/Logo";
import { HexPattern } from "@/components/HexPattern";
import { ParallaxImage } from "@/components/ParallaxImage";
import { ScrollProgress } from "@/components/ScrollProgress";
import { BuzzChat } from "@/components/BuzzChat";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "EzzyHub — Find missing requirements. Get AI fixes." },
      {
        name: "description",
        content:
          "EzzyHub compares your code to your requirements, finds every gap, and writes the code to fill it.",
      },
      { property: "og:title", content: "EzzyHub — Find missing requirements. Get AI fixes." },
      {
        property: "og:description",
        content:
          "Upload your code and requirements. Buzz discovers missing features and suggests exact AI code implementations.",
      },
    ],
  }),
  component: Landing,
});

/* ---------- reveal on scroll ---------- */

function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: React.ReactNode;
  delay?: number;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(([e]) => e.isIntersecting && setShown(true), {
      threshold: 0.15,
    });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <div
      ref={ref}
      style={{ animationDelay: `${delay}ms` }}
      className={(shown ? "animate-fade-up " : "opacity-0 ") + className}
    >
      {children}
    </div>
  );
}

/* ---------- nav — minimal, floats over whatever section is behind it ---------- */

function Nav() {
  const [solid, setSolid] = useState(false);
  useEffect(() => {
    const onScroll = () => setSolid(window.scrollY > 40);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  return (
    <nav
      className="fixed top-0 inset-x-0 z-50 transition-all duration-500"
      style={{
        background: solid ? "rgba(8,7,5,0.65)" : "transparent",
        backdropFilter: solid ? "blur(18px)" : "none",
        borderBottom: solid ? "1px solid rgba(245,191,49,0.12)" : "1px solid transparent",
      }}
    >
      <div className="max-w-7xl mx-auto px-6 py-5 flex items-center justify-between">
        <Logo size="md" />
        <div className="hidden md:flex items-center gap-9 text-sm text-foreground/70">
          <a href="#how" className="hover:text-[var(--gold)] transition-colors">
            How it works
          </a>
          <a href="#flow" className="hover:text-[var(--gold)] transition-colors">
            Flow
          </a>
          <a href="#analysis" className="hover:text-[var(--gold)] transition-colors">
            Static Analysis
          </a>
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/login"
            className="text-sm text-foreground/80 hover:text-[var(--gold)] px-3 py-2 transition-colors"
          >
            Sign in
          </Link>
          <Link
            to="/signup"
            className="bg-[var(--gold)] text-primary-foreground text-sm font-medium px-4 py-2 rounded-full hover:shadow-[0_0_25px_rgba(245,191,49,0.5)] transition-shadow"
          >
            Get Started
          </Link>
        </div>
      </div>
    </nav>
  );
}

/* ---------- hero — the photo IS the design, not a faded backdrop ---------- */

function Hero() {
  return (
    <section className="relative min-h-[100vh] flex items-end overflow-hidden">
      <div className="absolute inset-0">
        <ParallaxImage
          src={heroBg}
          strength={60}
          scrimClassName="bg-gradient-to-t from-background via-background/60 to-black/60"
        />
      </div>

      <div className="relative z-10 w-full max-w-7xl mx-auto px-6 pb-24 pt-40">
        <Reveal>
          <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full border border-[var(--gold)]/35 bg-black/30 backdrop-blur-sm text-[var(--gold)] text-xs tracking-[0.25em] uppercase mb-8">
            <span className="w-1.5 h-1.5 rounded-full bg-[var(--gold)]" />
            The AI Gap Finder
          </div>
        </Reveal>
        <Reveal delay={100}>
          <h1
            className="text-6xl md:text-8xl lg:text-[8.5rem] font-medium leading-[0.92] mb-6 max-w-5xl"
            style={{ textShadow: "0 6px 40px rgba(0,0,0,0.55)" }}
          >
            Find what's <span className="shimmer-gold italic">missing</span>.
            <br />
            Ship what's <span className="shimmer-gold italic">complete</span>.
          </h1>
        </Reveal>
        <Reveal delay={200}>
          <p className="text-lg md:text-xl text-foreground/85 max-w-xl mb-10 leading-relaxed">
            Drop in your code and requirements. EzzyHub finds every unimplemented feature and writes
            the exact code to fill it.
          </p>
        </Reveal>
        <Reveal delay={300}>
          <div className="flex flex-wrap gap-3">
            <Link
              to="/signup"
              className="inline-flex items-center gap-3 bg-[var(--gold)] text-primary-foreground text-base font-medium pl-6 pr-2 py-2 rounded-full hover:shadow-[0_0_40px_rgba(245,191,49,0.55)] transition-shadow"
            >
              Analyze my code
              <span className="bg-background rounded-full p-2">
                <ArrowRight className="w-4 h-4 text-[var(--gold)]" />
              </span>
            </Link>
            <a
              href="#idea"
              className="inline-flex items-center gap-2 text-foreground/90 hover:text-white text-base font-medium px-6 py-3 rounded-full border border-white/25 hover:border-[var(--gold)]/60 backdrop-blur-sm transition-colors"
            >
              Watch the idea
            </a>
          </div>
        </Reveal>
      </div>

      <a
        href="#idea"
        aria-label="Scroll to next section"
        className="absolute bottom-6 left-1/2 -translate-x-1/2 z-10 text-foreground/60 hover:text-[var(--gold)] transition-colors animate-bounce"
      >
        <ArrowDown className="w-5 h-5" />
      </a>
    </section>
  );
}

/* ---------- idea video — set inside the forest, not floating on flat black ---------- */

function IdeaVideo() {
  const ref = useRef<HTMLVideoElement>(null);
  const [playing, setPlaying] = useState(false);
  const [everPlayed, setEverPlayed] = useState(false);
  const [muted, setMuted] = useState(true);

  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting) {
          v.play()
            .then(() => {
              setPlaying(true);
              setEverPlayed(true);
            })
            .catch(() => {});
        } else {
          v.pause();
          setPlaying(false);
        }
      },
      { threshold: 0.4 },
    );
    io.observe(v);
    return () => io.disconnect();
  }, []);

  const togglePlay = () => {
    const v = ref.current;
    if (!v) return;
    if (v.paused) {
      v.play()
        .then(() => {
          setPlaying(true);
          setEverPlayed(true);
        })
        .catch(() => {});
    } else {
      v.pause();
      setPlaying(false);
    }
  };

  return (
    <section id="idea" className="relative py-28 md:py-40 overflow-hidden bg-background">
      <HexPattern />
      <div className="relative max-w-4xl mx-auto px-6">
        <Reveal>
          <div className="text-center mb-14">
            <p className="text-[var(--gold)] text-xs tracking-[0.4em] uppercase mb-4 font-medium">
              The EzzyHub Idea
            </p>
            <h2 className="text-4xl md:text-6xl font-medium leading-[0.95]">Buzz tells it best.</h2>
            <p className="text-sm text-foreground/60 mt-4">
              Short and to the point — pause any time to read along.
            </p>
          </div>
        </Reveal>
        <Reveal delay={100}>
          <div
            className="relative max-w-2xl mx-auto rounded-3xl overflow-hidden border border-[var(--gold)]/25"
            style={{ boxShadow: "0 60px 140px -40px rgba(0,0,0,0.8)" }}
          >
            <video
              ref={ref}
              src={storyVideo}
              playsInline
              loop
              muted={muted}
              onClick={togglePlay}
              className="w-full h-auto block cursor-pointer"
            />
            {!everPlayed && (
              <button
                onClick={togglePlay}
                className="absolute inset-0 flex items-center justify-center bg-black/40"
                aria-label="Play video"
              >
                <div className="w-16 h-16 rounded-full bg-[var(--gold)] flex items-center justify-center hover:scale-110 transition-transform shadow-[0_0_50px_rgba(245,191,49,0.6)]">
                  <Play className="w-6 h-6 text-primary-foreground" fill="currentColor" />
                </div>
              </button>
            )}
            <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 px-3 py-2.5 bg-gradient-to-t from-black/85 to-transparent">
              <button
                onClick={togglePlay}
                className="rounded-full bg-black/50 backdrop-blur border border-[var(--gold)]/30 p-2 text-white/90 hover:text-[var(--gold)] transition-colors"
                aria-label={playing ? "Pause video" : "Play video"}
              >
                {playing ? (
                  <Pause className="w-3.5 h-3.5" />
                ) : (
                  <Play className="w-3.5 h-3.5" fill="currentColor" />
                )}
              </button>
              <button
                onClick={() => setMuted((m) => !m)}
                className="rounded-full bg-black/50 backdrop-blur border border-[var(--gold)]/30 px-3 py-1.5 text-[10px] tracking-[0.22em] uppercase text-white/90 hover:text-[var(--gold)] transition-colors flex items-center gap-2"
              >
                {muted ? <VolumeX className="w-3.5 h-3.5" /> : <Volume2 className="w-3.5 h-3.5" />}
                {muted ? "Unmute" : "Mute"}
              </button>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- how it works — editorial numbers, no cards, no photo (breather) ---------- */

function HowItWorks() {
  const steps = [
    {
      icon: FileCode2,
      t: "Drop your spec",
      d: "Paste requirements or upload a doc. Each one becomes a cell in the hive.",
    },
    {
      icon: ScanSearch,
      t: "Connect your code",
      d: "Repo, branch, or zip. Buzz scans every file and matches against the hive.",
    },
    {
      icon: Sparkles,
      t: "Get AI-written fixes",
      d: "Missing cells come back with code — in your stack, in your style, ready to merge.",
    },
  ];
  return (
    <section id="how" className="relative px-6 py-28 md:py-40 overflow-hidden bg-background">
      <div className="relative max-w-6xl mx-auto grid md:grid-cols-[1.1fr_0.9fr] gap-16 items-center">
        <div>
          <Reveal>
            <div className="mb-14 max-w-xl">
              <p className="text-[var(--gold)] text-xs tracking-[0.4em] uppercase mb-4 font-medium">
                How it works
              </p>
              <h2 className="text-4xl md:text-6xl font-medium leading-[0.95]">
                Three steps.
                <br />
                <span className="text-foreground/40">No guessing.</span>
              </h2>
            </div>
          </Reveal>
          <div className="space-y-10">
            {steps.map((s, i) => (
              <Reveal key={i} delay={i * 120}>
                <div className="flex items-start gap-6 border-t border-[var(--gold)]/15 pt-8">
                  <span className="text-[var(--gold)]/35 text-5xl md:text-6xl font-display leading-none shrink-0 w-[70px]">
                    0{i + 1}
                  </span>
                  <div className="flex items-start gap-4 flex-1">
                    <div className="w-11 h-11 rounded-xl bg-[var(--gold)]/10 border border-[var(--gold)]/20 flex items-center justify-center shrink-0 mt-1">
                      <s.icon className="w-5 h-5 text-[var(--gold)]" strokeWidth={1.6} />
                    </div>
                    <div>
                      <h3 className="text-2xl font-medium mb-2">{s.t}</h3>
                      <p className="text-muted-foreground leading-relaxed max-w-md">{s.d}</p>
                    </div>
                  </div>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
        <Reveal delay={150}>
          <div
            className="relative aspect-[4/5] rounded-3xl overflow-hidden border border-[var(--gold)]/25"
            style={{ boxShadow: "0 50px 120px -30px rgba(0,0,0,0.7)" }}
          >
            <img src={hexGridBg} alt="" className="w-full h-full object-cover" />
            <div className="absolute inset-0 bg-black/45" />
            <div className="absolute inset-0 bg-gradient-to-t from-black/55 via-transparent to-transparent" />
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- ezzyhub flow ---------- */

function FlowNodeItem({
  icon: Icon,
  label,
  desc,
}: {
  icon: typeof Upload;
  label: string;
  desc: string;
}) {
  return (
    <div className="flex md:flex-col items-center md:items-center gap-4 md:gap-3 md:w-32 shrink-0">
      <div className="hex-cell w-14 h-16 shrink-0 bg-gradient-to-br from-[var(--gold)]/15 to-transparent border border-[var(--gold)]/30 flex items-center justify-center">
        <Icon className="w-5 h-5 text-[var(--gold)]" strokeWidth={1.7} />
      </div>
      <div className="text-left md:text-center">
        <p className="text-sm font-medium text-foreground whitespace-nowrap">{label}</p>
        <p className="text-xs text-muted-foreground">{desc}</p>
      </div>
    </div>
  );
}

function FlowConnector() {
  return (
    <div className="w-px h-8 ml-7 md:ml-0 md:mt-7 md:w-auto md:h-px md:flex-1 bg-gradient-to-b md:bg-gradient-to-r from-[var(--gold)]/45 to-[var(--gold)]/10" />
  );
}

function EzzyHubFlow() {
  const nodes = [
    { icon: Upload, label: "Upload", desc: "Code + requirements" },
    { icon: Code2, label: "Code Analyzer", desc: "Every file mapped" },
    { icon: GitCompareArrows, label: "Req ↔ Code", desc: "Matched to your spec" },
    { icon: Radar, label: "Sonar Analyzer", desc: "Quality & security checked" },
    { icon: Sparkles, label: "AI Suggestions", desc: "Missing code written" },
    { icon: FileCheck2, label: "Report", desc: "One verified overview" },
  ];
  return (
    <section id="flow" className="relative px-6 py-28 md:py-40 overflow-hidden bg-background">
      <HexPattern />
      <div className="relative max-w-6xl mx-auto">
        <Reveal>
          <div className="text-center mb-16">
            <p className="text-[var(--gold)] text-xs tracking-[0.4em] uppercase mb-4 font-medium">
              The Pipeline
            </p>
            <h2 className="text-4xl md:text-6xl font-medium leading-[0.95] max-w-2xl mx-auto">
              One upload.
              <br />
              <span className="text-foreground/40">Five services. Zero gaps.</span>
            </h2>
          </div>
        </Reveal>
        <Reveal delay={100}>
          <div className="flex flex-col md:flex-row md:items-start gap-8 md:gap-0">
            {nodes.map((n, i) => (
              <div key={n.label} className="flex flex-col md:contents">
                <FlowNodeItem icon={n.icon} label={n.label} desc={n.desc} />
                {i < nodes.length - 1 && <FlowConnector />}
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- static analysis (sonar) — full-bleed photo, floating glass card ---------- */

function StaticAnalysis() {
  const checks = [
    { icon: Bug, text: "Bugs & reliability issues" },
    { icon: ShieldAlert, text: "Security vulnerabilities & hotspots" },
    { icon: ScanSearch, text: "Code smells & maintainability" },
    { icon: Copy, text: "Duplicated code blocks" },
    { icon: Percent, text: "Test coverage estimate" },
  ];
  const metrics = [
    { label: "Bugs", value: 2, icon: Bug, color: "text-red-400" },
    { label: "Vulnerabilities", value: 0, icon: ShieldAlert, color: "text-orange-400" },
    { label: "Code Smells", value: 14, icon: ScanSearch, color: "text-[var(--copper)]" },
    { label: "Hotspots", value: 1, icon: ShieldAlert, color: "text-[var(--gold)]" },
    { label: "Coverage", value: "82%", icon: Percent, color: "text-green-400" },
    { label: "Duplications", value: "3.4%", icon: Copy, color: "text-foreground" },
  ];
  return (
    <section id="analysis" className="relative py-28 md:py-40 overflow-hidden bg-background">
      <HexPattern />
      <div className="relative max-w-6xl mx-auto px-6 grid md:grid-cols-2 gap-16 items-center">
        <Reveal>
          <div>
            <p className="text-[var(--gold)] text-xs tracking-[0.4em] uppercase mb-4 font-medium">
              Static Analysis
            </p>
            <h2 className="text-4xl md:text-5xl font-medium leading-[1.05] mb-5">
              Bugs and vulnerabilities,
              <br />
              <span className="italic shimmer-gold">caught before merge.</span>
            </h2>
            <p className="text-foreground/70 leading-relaxed max-w-md mb-6">
              A Sonar-style static analysis engine runs alongside the gap scan — quality gate, code
              smells, security hotspots, and coverage, all in one pass.
            </p>
            <ul className="space-y-3">
              {checks.map((c) => (
                <li key={c.text} className="flex items-center gap-3 text-sm text-foreground/85">
                  <span className="w-8 h-8 rounded-lg bg-[var(--gold)]/10 border border-[var(--gold)]/25 flex items-center justify-center shrink-0">
                    <c.icon className="w-4 h-4 text-[var(--gold)]" />
                  </span>
                  {c.text}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>
        <Reveal delay={150}>
          <div className="rounded-3xl border border-[var(--gold)]/15 bg-card/70 backdrop-blur p-6 shadow-[0_40px_100px_-40px_rgba(212,168,64,0.35)]">
            <div className="flex items-center justify-between mb-5">
              <span className="inline-flex items-center gap-2 text-sm font-medium px-3 py-1.5 rounded-full border text-green-400 bg-green-500/10 border-green-500/25">
                <ShieldCheck className="w-4 h-4" />
                Quality Gate: Passed
              </span>
              <span className="text-xs text-muted-foreground">example-health-jee</span>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {metrics.map((m) => (
                <div
                  key={m.label}
                  className="rounded-xl border border-[var(--gold)]/15 bg-background/40 p-4"
                >
                  <m.icon className={`w-4 h-4 mb-2 ${m.color}`} strokeWidth={1.8} />
                  <p className="text-[11px] text-muted-foreground mb-1">{m.label}</p>
                  <p className={`text-lg font-medium ${m.color}`}>{m.value}</p>
                </div>
              ))}
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- result hive — breather, illustration takes the stage ---------- */

function ResultHive() {
  return (
    <section className="relative px-6 py-28 md:py-40 overflow-hidden bg-background">
      <div className="relative max-w-6xl mx-auto grid md:grid-cols-2 gap-16 items-center">
        <Reveal>
          <div>
            <p className="text-[var(--gold)] text-xs tracking-[0.4em] uppercase mb-4 font-medium">
              The Result
            </p>
            <h2 className="text-4xl md:text-5xl font-medium leading-[1.05] mb-5">
              Gold cells shipped.
              <br />
              <span className="italic shimmer-gold">Black cells filled.</span>
            </h2>
            <p className="text-foreground/70 leading-relaxed max-w-md">
              Every requirement becomes a cell. Gold means done. Black means missing — and Buzz
              brings back the code to fill it.
            </p>
          </div>
        </Reveal>
        <Reveal delay={150}>
          <div
            className="relative aspect-[4/5] rounded-3xl overflow-hidden border border-[var(--gold)]/25"
            style={{ boxShadow: "0 50px 120px -30px rgba(0,0,0,0.7)" }}
          >
            <img src={mixedCellsBg} alt="" className="w-full h-full object-cover" />
            <div className="absolute inset-0 bg-black/40" />
            <div className="absolute inset-0 bg-gradient-to-t from-black/55 via-transparent to-transparent" />
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- cta — the big cinematic close ---------- */

function CTA() {
  return (
    <section className="relative py-40 md:py-56 overflow-hidden flex items-center justify-center">
      <ParallaxImage
        src={honeyDropBg}
        strength={45}
        scrimClassName="bg-gradient-to-b from-background/65 via-background/80 to-background"
      />
      <div className="relative max-w-3xl mx-auto text-center px-6">
        <Reveal>
          <h2
            className="text-5xl md:text-7xl font-medium leading-[0.95] mb-6"
            style={{ textShadow: "0 6px 40px rgba(0,0,0,0.6)" }}
          >
            Fill every cell.
            <br />
            <span className="italic shimmer-gold">Ship complete code.</span>
          </h2>
        </Reveal>
        <Reveal delay={100}>
          <p className="text-foreground/85 mb-10 max-w-xl mx-auto">
            Private by default. Zero data retention. Start scanning in under a minute.
          </p>
        </Reveal>
        <Reveal delay={200}>
          <Link
            to="/signup"
            className="inline-flex items-center gap-3 bg-[var(--gold)] text-primary-foreground text-lg font-medium pl-7 pr-2 py-2 rounded-full hover:shadow-[0_0_50px_rgba(245,191,49,0.6)] transition-shadow"
          >
            Start scanning — free
            <span className="bg-background rounded-full p-2">
              <ArrowRight className="w-5 h-5 text-[var(--gold)]" />
            </span>
          </Link>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- footer ---------- */

function Footer() {
  return (
    <footer className="relative px-6 py-10 border-t border-[var(--gold)]/10 bg-black">
      <div className="relative max-w-7xl mx-auto flex flex-col md:flex-row items-center justify-between gap-4">
        <Logo size="md" />
        <span className="text-muted-foreground/60 text-sm">© 2026 EzzyHub · built with Buzz</span>
      </div>
    </footer>
  );
}

/* ---------- page ---------- */
/* Buzz only appears here on the home page — removed from login, signup, and
   the dashboard so those pages stay focused and uncluttered. */

function Landing() {
  return (
    <div className="relative flex flex-col overflow-x-hidden bg-background">
      <ScrollProgress />
      <Nav />
      <Hero />
      <IdeaVideo />
      <HowItWorks />
      <EzzyHubFlow />
      <StaticAnalysis />
      <ResultHive />
      <CTA />
      <Footer />
      <BuzzChat />
    </div>
  );
}
