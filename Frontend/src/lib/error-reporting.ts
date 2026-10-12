// This originally forwarded errors to Lovable's own dashboard via a
// window.__lovableEvents hook that Lovable injects into its preview iframe.
// That hook doesn't exist outside Lovable, so this now just logs client-side
// errors to the console. Swap the body for Sentry/LogRocket/etc. if you want
// real error tracking in production.
export function reportError(error: unknown, context: Record<string, unknown> = {}) {
  if (typeof window === "undefined") return;
  console.error(error, {
    source: "react_error_boundary",
    route: window.location.pathname,
    ...context,
  });
}
