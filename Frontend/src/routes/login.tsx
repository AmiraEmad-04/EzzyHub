import { createFileRoute, Link, useRouter } from "@tanstack/react-router";
import { useState } from "react";
import { ArrowLeft, ArrowRight, Lock, Mail } from "lucide-react";
import hiveTunnelBg from "@/assets/backgrounds/hive-tunnel-bg.jpg";
import { Logo } from "@/components/Logo";
import { ParallaxImage } from "@/components/ParallaxImage";
import { login } from "@/lib/auth";

export const Route = createFileRoute("/login")({
  head: () => ({
    meta: [
      { title: "Sign In — EzzyHub" },
      {
        name: "description",
        content:
          "Sign in to EzzyHub to scan your project code against requirements and get AI fixes.",
      },
    ],
  }),
  component: LoginPage,
});

function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await login(email, password);
      router.navigate({ to: "/dashboard" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid email or password.");
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen overflow-hidden flex flex-col">
      <ParallaxImage
        src={hiveTunnelBg}
        strength={30}
        scrimClassName="bg-gradient-to-b from-black/78 via-black/82 to-background"
      />

      {/* top bar: logo left, back-to-home right */}
      <div className="relative z-20 flex items-center justify-between px-6 py-5 md:px-10">
        <Logo size="sm" />
        <Link
          to="/"
          className="inline-flex items-center gap-2 text-sm text-white/85 hover:text-[var(--gold)] border border-white/25 hover:border-[var(--gold)]/50 bg-black/25 backdrop-blur-sm rounded-full px-4 py-2 transition-colors"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          Back to home
        </Link>
      </div>

      <div className="relative z-10 flex-1 flex items-center justify-center px-6 pb-16">
        <div className="w-full max-w-md">
          <div className="text-center mb-8">
            <h1
              className="text-4xl md:text-5xl font-display font-medium text-foreground"
              style={{ textShadow: "0 4px 30px rgba(0,0,0,0.7)" }}
            >
              Welcome back to <span className="italic shimmer-gold">the hive</span>.
            </h1>
            <p className="text-sm text-white/70 mt-3">Sign in to discover gaps in your codebase.</p>
          </div>

          <div className="rounded-3xl border border-[var(--gold)]/25 bg-black/50 backdrop-blur-2xl p-8 shadow-[0_40px_120px_-30px_rgba(0,0,0,0.9)]">
            {error && (
              <div className="mb-6 p-4 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm text-center">
                {error}
              </div>
            )}

            <form onSubmit={handleLogin} className="space-y-5">
              <div>
                <label className="block text-xs font-medium uppercase tracking-wider text-foreground/80 mb-2">
                  Email Address
                </label>
                <div className="relative">
                  <Mail className="absolute left-4 top-3.5 w-4 h-4 text-muted-foreground" />
                  <input
                    type="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="buzz@ezzyhub.com"
                    className="w-full bg-black/30 border border-white/15 rounded-xl pl-11 pr-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                  />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="block text-xs font-medium uppercase tracking-wider text-foreground/80">
                    Password
                  </label>
                  <a href="#" className="text-xs text-[var(--gold)] hover:underline">
                    Forgot?
                  </a>
                </div>
                <div className="relative">
                  <Lock className="absolute left-4 top-3.5 w-4 h-4 text-muted-foreground" />
                  <input
                    type="password"
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••"
                    className="w-full bg-black/30 border border-white/15 rounded-xl pl-11 pr-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={loading}
                className="w-full bg-gradient-to-r from-[var(--gold-deep)] via-[var(--gold)] to-[var(--gold-soft)] text-primary-foreground font-medium py-3.5 rounded-xl hover:shadow-[0_0_30px_rgba(245,191,49,0.4)] transition-all flex items-center justify-center gap-2 cursor-pointer disabled:opacity-50"
              >
                {loading ? "Signing in..." : "Sign In"}
                <ArrowRight className="w-4 h-4 text-primary-foreground" />
              </button>
            </form>
          </div>

          <p className="text-center text-sm text-white/70 mt-8">
            Don't have a cell in the hive yet?{" "}
            <Link to="/signup" className="text-[var(--gold)] font-medium hover:underline">
              Sign up
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
