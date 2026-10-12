import { createFileRoute, useRouter } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Menu } from "lucide-react";
import { Logo } from "@/components/Logo";
import { HexPattern } from "@/components/HexPattern";
import { DashboardSidebar } from "@/components/dashboard/Sidebar";
import { CodeAnalyzerPanel } from "@/components/dashboard/CodeAnalyzerPanel";
import { MatchingPanel } from "@/components/dashboard/MatchingPanel";
import { SonarPanel } from "@/components/dashboard/SonarPanel";
import { AiSuggestionsPanel } from "@/components/dashboard/AiSuggestionsPanel";
import { HistoryPanel } from "@/components/dashboard/HistoryPanel";
import { fetchCurrentUser, type AuthUser } from "@/lib/auth";

export const Route = createFileRoute("/dashboard")({
  head: () => ({
    meta: [{ title: "Dashboard — EzzyHub" }],
  }),
  component: DashboardPage,
});

export type ServiceId = "code-analyzer" | "req-matching" | "sonar-analyzer" | "ai-suggestions";

function DashboardPage() {
  const router = useRouter();
  const [checking, setChecking] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [active, setActive] = useState<ServiceId | "history">("history");
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    fetchCurrentUser().then((u) => {
      if (!u) {
        router.navigate({ to: "/login" });
        return;
      }
      setUser(u);
      setChecking(false);
    });
  }, [router]);

  if (checking || !user) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <div className="w-8 h-8 rounded-full border-2 border-[var(--gold)]/30 border-t-[var(--gold)] animate-spin" />
      </div>
    );
  }

  const firstName = user.fullName?.split(" ")?.[0] ?? user.email;

  return (
    <div className="relative h-screen flex bg-background text-foreground overflow-hidden">
      {/* simple bee-pattern texture behind the whole workspace */}
      <div className="fixed inset-0 pointer-events-none">
        <HexPattern opacity={0.05} />
      </div>

      {/* desktop sidebar — fixed, its own independent scroll if the nav ever overflows */}
      <DashboardSidebar active={active} onSelect={setActive} />

      {/* mobile drawer */}
      {mobileNavOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-black/60" onClick={() => setMobileNavOpen(false)} />
          <div className="absolute inset-y-0 left-0 animate-panel-in">
            <DashboardSidebar
              active={active}
              onSelect={(id) => {
                setActive(id);
                setMobileNavOpen(false);
              }}
            />
          </div>
        </div>
      )}

      {/* only this column scrolls — the sidebar above stays put */}
      <div className="relative z-10 flex-1 min-w-0 h-full overflow-y-auto">
        {/* mobile top bar */}
        <div className="lg:hidden flex items-center justify-between px-5 py-4 border-b border-[var(--gold)]/10">
          <Logo size="sm" withLink={false} />
          <button
            onClick={() => setMobileNavOpen(true)}
            className="p-2 text-foreground/80"
            aria-label="Open menu"
          >
            <Menu className="w-5 h-5" />
          </button>
        </div>

        <div className="max-w-6xl mx-auto px-6 py-8 md:px-10 md:py-10">
          {/* welcome header */}
          <div className="mb-8">
            <h2 className="text-2xl font-display font-medium">Welcome back, {firstName}!</h2>
          </div>

          {/* Every panel stays mounted all the time — switching tabs only
              hides/shows them (display:none via `hidden`), it never
              unmounts them. That's what lets you start a run in Code
              Analyzer, switch to Matching and start a second run, and have
              both keep going in the background at the same time. */}
          <div className={active === "code-analyzer" ? "" : "hidden"}>
            <CodeAnalyzerPanel />
          </div>
          <div className={active === "req-matching" ? "" : "hidden"}>
            <MatchingPanel />
          </div>
          <div className={active === "sonar-analyzer" ? "" : "hidden"}>
            <SonarPanel />
          </div>
          <div className={active === "ai-suggestions" ? "" : "hidden"}>
            <AiSuggestionsPanel />
          </div>
          <div className={active === "history" ? "" : "hidden"}>
            <HistoryPanel />
          </div>
        </div>
      </div>
    </div>
  );
}
