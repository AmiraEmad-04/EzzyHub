import { createFileRoute, Link, useRouter } from "@tanstack/react-router";
import { useState } from "react";
import { ArrowLeft, ArrowRight, Lock, Mail, User } from "lucide-react";
import hiveTunnelBg from "@/assets/backgrounds/hive-tunnel-bg.jpg";
import { Logo } from "@/components/Logo";
import { ParallaxImage } from "@/components/ParallaxImage";
import { registerAccount } from "@/lib/auth";

export const Route = createFileRoute("/signup")({
  head: () => ({
    meta: [
      { title: "Create Account — EzzyHub" },
      {
        name: "description",
        content:
          "Create your free EzzyHub account to automatically analyze code against requirements.",
      },
    ],
  }),
  component: SignupPage,
});

function SignupPage() {
  const router = useRouter();
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await registerAccount(fullName, email, password);
      // auth-service activates the account immediately — no email
      // confirmation step (no SMTP configured anywhere in the backend) —
      // so straight into the dashboard, same as a normal login.
      router.navigate({ to: "/dashboard" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create your account.");
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen overflow-hidden flex flex-col">
      <ParallaxImage
        src={hiveTunnelBg}
        strength={30}
        scrimClassName="bg-gradient-to-b from-black/78 via-black/82 to-background"
        imageClassName="scale-x-[-1]"
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
              Claim your <span className="italic shimmer-gold">honey cell</span>.
            </h1>
            <p className="text-sm text-white/70 mt-3">Free forever for personal projects.</p>
          </div>

          <div className="rounded-3xl border border-[var(--gold)]/25 bg-black/50 backdrop-blur-2xl p-8 shadow-[0_40px_120px_-30px_rgba(0,0,0,0.9)]">
            {error && (
              <div className="mb-6 p-4 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm text-center">
                {error}
              </div>
            )}

            <form onSubmit={handleSignup} className="space-y-4">
              <div>
                <label className="block text-xs font-medium uppercase tracking-wider text-foreground/80 mb-1.5">
                  Full Name
                </label>
                <div className="relative">
                  <User className="absolute left-4 top-3.5 w-4 h-4 text-muted-foreground" />
                  <input
                    type="text"
                    required
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    placeholder="Buzz Bee"
                    className="w-full bg-black/30 border border-white/15 rounded-xl pl-11 pr-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium uppercase tracking-wider text-foreground/80 mb-1.5">
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
                <label className="block text-xs font-medium uppercase tracking-wider text-foreground/80 mb-1.5">
                  Password
                </label>
                <div className="relative">
                  <Lock className="absolute left-4 top-3.5 w-4 h-4 text-muted-foreground" />
                  <input
                    type="password"
                    required
                    minLength={8}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="At least 8 characters"
                    className="w-full bg-black/30 border border-white/15 rounded-xl pl-11 pr-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
                  />
                </div>
              </div>

              <div className="pt-2">
                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-gradient-to-r from-[var(--gold-deep)] via-[var(--gold)] to-[var(--gold-soft)] text-primary-foreground font-medium py-3.5 rounded-xl hover:shadow-[0_0_30px_rgba(245,191,49,0.4)] transition-all flex items-center justify-center gap-2 cursor-pointer disabled:opacity-50"
                >
                  {loading ? "Creating account..." : "Create Account"}
                  <ArrowRight className="w-4 h-4 text-primary-foreground" />
                </button>
              </div>
            </form>
          </div>

          <p className="text-center text-sm text-white/70 mt-8">
            Already have an account?{" "}
            <Link to="/login" className="text-[var(--gold)] font-medium hover:underline">
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
