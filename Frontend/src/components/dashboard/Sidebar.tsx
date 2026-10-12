import { useRouter } from "@tanstack/react-router";
import { Code2, GitCompareArrows, Radar, Sparkles, History, LogOut } from "lucide-react";
import { Logo } from "@/components/Logo";
import { logout } from "@/lib/auth";
import type { ServiceId } from "@/routes/dashboard";

const SERVICES: Array<{ id: ServiceId; label: string; icon: typeof Code2 }> = [
  { id: "code-analyzer", label: "Code Analyzer", icon: Code2 },
  { id: "req-matching", label: "Req ↔ Code Matching", icon: GitCompareArrows },
  { id: "sonar-analyzer", label: "Sonar Analyzer", icon: Radar },
  { id: "ai-suggestions", label: "AI Suggestions", icon: Sparkles },
];

export function DashboardSidebar({
  active,
  onSelect,
}: {
  active: ServiceId | "history";
  onSelect: (id: ServiceId | "history") => void;
}) {
  const router = useRouter();

  const handleLogout = () => {
    logout();
    router.navigate({ to: "/" });
  };

  return (
    <aside className="w-72 shrink-0 hidden lg:flex flex-col h-full border-r border-[var(--gold)]/12 bg-background/95">
      <div className="px-6 py-6 border-b border-[var(--gold)]/10">
        <Logo size="md" withLink={false} />
        <p className="text-xs text-muted-foreground mt-1.5 tracking-wide">
          AI Verification Platform
        </p>
      </div>

      <nav className="flex-1 overflow-y-auto px-4 py-6 space-y-8">
        <div>
          <p className="px-2 text-[10px] font-medium uppercase tracking-[0.2em] text-muted-foreground/70 mb-2">
            Reports
          </p>
          <div className="space-y-1">
            <button
              onClick={() => onSelect("history")}
              className={
                "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition-colors text-left " +
                (active === "history"
                  ? "bg-[var(--gold)]/15 text-[var(--gold)] border border-[var(--gold)]/30 font-medium"
                  : "text-foreground/70 hover:bg-[var(--gold)]/5 hover:text-foreground border border-transparent")
              }
            >
              <History className="w-4 h-4 shrink-0" strokeWidth={1.8} />
              History
            </button>
          </div>
        </div>

        <div>
          <p className="px-2 text-[10px] font-medium uppercase tracking-[0.2em] text-muted-foreground/70 mb-2">
            Services
          </p>
          <div className="space-y-1">
            {SERVICES.map((s) => {
              const isActive = active === s.id;
              return (
                <button
                  key={s.id}
                  onClick={() => onSelect(s.id)}
                  className={
                    "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition-colors text-left " +
                    (isActive
                      ? "bg-[var(--gold)]/15 text-[var(--gold)] border border-[var(--gold)]/30 font-medium"
                      : "text-foreground/70 hover:bg-[var(--gold)]/5 hover:text-foreground border border-transparent")
                  }
                >
                  <s.icon className="w-4 h-4 shrink-0" strokeWidth={1.8} />
                  {s.label}
                </button>
              );
            })}
          </div>
        </div>
      </nav>

      <div className="px-4 py-5 border-t border-[var(--gold)]/10 shrink-0">
        <button
          onClick={handleLogout}
          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm text-destructive/85 hover:bg-destructive/10 hover:text-destructive transition-colors"
        >
          <LogOut className="w-4 h-4" strokeWidth={1.8} />
          Logout
        </button>
      </div>
    </aside>
  );
}
