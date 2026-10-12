/**
 * Client for the EzzyHub auth-service, reached through the api-gateway.
 *
 * Replaces the previous Supabase-based auth. The backend is custom now
 * (see auth-service), so this talks to it directly with fetch + a JWT
 * stored in localStorage — no SDK needed.
 *
 * Set VITE_API_BASE_URL in a .env file to point at something other than
 * the local gateway (defaults to http://localhost:8085, the gateway's
 * dev port from api-gateway/application.yml).
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8085";
const TOKEN_KEY = "ezzyhub_token";

export type AuthUser = {
  id: number;
  fullName: string;
  email: string;
  role: string;
  createdAt: string;
};

export type AuthResult = {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: AuthUser;
};

/** Shape of the error body auth-service's GlobalExceptionHandler returns. */
type ApiErrorBody = {
  message?: string;
  error?: string;
};

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

function setToken(token: string) {
  window.localStorage.setItem(TOKEN_KEY, token);
}

function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

async function parseErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as ApiErrorBody;
    return body.message || body.error || fallback;
  } catch {
    return fallback;
  }
}

/**
 * Create an account. Active immediately — auth-service doesn't do email
 * confirmation (no SMTP configured anywhere in the backend), so this logs
 * the person in right away, same as login() does.
 */
export async function registerAccount(
  fullName: string,
  email: string,
  password: string,
): Promise<AuthResult> {
  const res = await fetch(`${API_BASE_URL}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, email, password }),
  });

  if (!res.ok) {
    throw new Error(await parseErrorMessage(res, "Could not create your account."));
  }

  const result = (await res.json()) as AuthResult;
  setToken(result.token);
  return result;
}

export async function login(email: string, password: string): Promise<AuthResult> {
  const res = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });

  if (!res.ok) {
    throw new Error(await parseErrorMessage(res, "Invalid email or password."));
  }

  const result = (await res.json()) as AuthResult;
  setToken(result.token);
  return result;
}

/**
 * Resolves the current session, if any. Returns null (rather than throwing)
 * for "not logged in" — missing token, expired token, or a token whose user
 * no longer exists — so callers can just redirect to /login on null.
 */
export async function fetchCurrentUser(): Promise<AuthUser | null> {
  const token = getToken();
  if (!token) return null;

  try {
    const res = await fetch(`${API_BASE_URL}/api/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) {
      if (res.status === 401) clearToken();
      return null;
    }
    return (await res.json()) as AuthUser;
  } catch {
    // Network error (gateway/auth-service not reachable) — treat as
    // logged-out rather than throwing, so the UI can still render a login
    // prompt instead of a blank crashed page.
    return null;
  }
}

export function logout() {
  clearToken();
}
