import type { LucideIcon } from "lucide-react";

export function PlaceholderPanel({
  icon: Icon,
  title,
  description,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
}) {
  return (
    <div className="flex flex-col items-center justify-center text-center py-24 rounded-lg border border-[var(--gold)]/12 bg-card">
      <div className="w-14 h-14 rounded-2xl bg-[var(--gold)]/10 border border-[var(--gold)]/25 flex items-center justify-center mb-5">
        <Icon className="w-6 h-6 text-[var(--gold)]" strokeWidth={1.6} />
      </div>
      <h2 className="text-2xl font-display font-medium mb-2">{title}</h2>
      <p className="text-sm text-muted-foreground max-w-sm">{description}</p>
      <span className="mt-5 text-xs px-3 py-1.5 rounded-full border border-[var(--gold)]/20 text-muted-foreground">
        Coming soon — wire this up once the backend is ready
      </span>
    </div>
  );
}
