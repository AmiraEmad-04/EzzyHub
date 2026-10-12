export type Step = string;

/**
 * Shows which stage of a multi-step backend pipeline is currently running.
 * Backends here are a single request/response (no server-sent progress
 * events), so this can't report real granular progress — it's honest about
 * that: each step just marks a coarse stage of the pipeline, and the step
 * right before the actual network call stays active for however long that
 * call actually takes. Same pattern as the reference Sonar page.
 */
export function StepProgress({
  steps,
  current,
  title = "Working on it...",
  subtitle,
}: {
  steps: Step[];
  current: number;
  title?: string;
  subtitle?: string;
}) {
  return (
    <div className="rounded-lg border border-[var(--gold)]/12 bg-card p-8 mt-6">
      <div className="text-center mb-6">
        <div className="w-10 h-10 rounded-full border-2 border-[var(--gold)]/20 border-t-[var(--gold)] animate-spin mx-auto mb-4" />
        <h3 className="font-medium mb-1">{title}</h3>
        {subtitle && <p className="text-sm text-muted-foreground">{subtitle}</p>}
      </div>
      <div className="max-w-xs mx-auto space-y-2.5 text-left">
        {steps.map((label, i) => {
          const stepNum = i + 1;
          const isDone = stepNum < current;
          const isActive = stepNum === current;
          return (
            <div
              key={label}
              className={
                "flex items-center gap-2.5 text-sm transition-colors " +
                (isDone
                  ? "text-foreground/70"
                  : isActive
                    ? "text-foreground font-medium"
                    : "text-muted-foreground")
              }
            >
              <span
                className={
                  "w-1.5 h-1.5 rounded-full shrink-0 " +
                  (isDone
                    ? "bg-green-400"
                    : isActive
                      ? "bg-[var(--gold)] shadow-[0_0_0_3px_rgba(245,191,49,0.15)]"
                      : "bg-muted-foreground/40")
                }
              />
              {label}
            </div>
          );
        })}
      </div>
    </div>
  );
}
