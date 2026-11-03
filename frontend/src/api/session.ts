/**
 * The signed-in session. The access token lives only in this module's memory: never in
 * localStorage or sessionStorage, where any injected script could read it (ROADMAP M8 gate). The
 * refresh token is an HttpOnly, SameSite=Strict cookie that identity sets on /api/v1/auth, which
 * page scripts cannot read at all. A reload loses the access token, and one refresh call gets a
 * new one from the cookie.
 */

type Listener = (token: string | null) => void;

let accessToken: string | null = null;
let refreshing: Promise<string | null> | null = null;
const listeners = new Set<Listener>();

export function currentToken(): string | null {
  return accessToken;
}

export function setToken(token: string | null): void {
  accessToken = token;
  listeners.forEach((listener) => listener(token));
}

export function onTokenChange(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request:
 * identity rotates the refresh token on every use and treats a reused one as theft, so two
 * parallel refreshes would sign the user out.
 */
export function refresh(fetchImpl: typeof fetch = fetch): Promise<string | null> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const response = await fetchImpl(new URL("/api/v1/auth/refresh", origin()), {
          method: "POST",
          credentials: "same-origin",
        });
        if (!response.ok) {
          setToken(null);
          return null;
        }
        const body = (await response.json()) as { accessToken: string };
        setToken(body.accessToken);
        return body.accessToken;
      } catch {
        // Offline or the gateway is down: keep whatever token we had; the next call retries.
        return accessToken;
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

/** For tests: forget everything. */
export function resetSession(): void {
  accessToken = null;
  refreshing = null;
  listeners.clear();
}

/** The page's own origin: every API call is same-origin (nginx or Vite proxies to the gateway). */
export function origin(): string {
  return globalThis.location?.origin ?? "http://localhost";
}
