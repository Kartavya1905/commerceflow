import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { describe, identity } from "../api/client";
import type { components } from "../api/schema/identity-service";
import { refresh, setToken } from "../api/session";

export type User = components["schemas"]["User"];

interface Auth {
  /** Undefined while the session is being restored from the refresh cookie on first load. */
  user: User | null | undefined;
  login(email: string, password: string): Promise<string | null>;
  register(email: string, password: string, displayName: string): Promise<string | null>;
  logout(): Promise<void>;
}

const AuthContext = createContext<Auth | null>(null);

/**
 * Who is signed in. The role shown here only decides what the UI offers; every admin call is
 * still checked by the API, which answers 403 to anyone else (tested end to end).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null | undefined>(undefined);

  const loadMe = useCallback(async () => {
    const { data } = await identity.GET("/api/v1/users/me");
    setUser(data ?? null);
  }, []);

  useEffect(() => {
    // A reload forgot the access token; the refresh cookie (if any) brings the session back.
    void (async () => {
      if (await refresh()) {
        await loadMe();
      } else {
        setUser(null);
      }
    })();
  }, [loadMe]);

  const login = useCallback(
    async (email: string, password: string) => {
      const { data, error } = await identity.POST("/api/v1/auth/login", {
        body: { email, password },
      });
      if (!data) {
        return describe(error, "Sign-in failed. Check your email and password.");
      }
      setToken(data.accessToken);
      await loadMe();
      return null;
    },
    [loadMe],
  );

  const register = useCallback(
    async (email: string, password: string, displayName: string) => {
      const { error, response } = await identity.POST("/api/v1/auth/register", {
        body: { email, password, displayName },
      });
      if (response.status !== 201) {
        return describe(error, "Could not create the account.");
      }
      return login(email, password);
    },
    [login],
  );

  const logout = useCallback(async () => {
    await identity.POST("/api/v1/auth/logout");
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, login, register, logout }), [user, login, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error("useAuth outside AuthProvider");
  }
  return auth;
}
