"use client";

import { createContext, useContext, useState, useEffect, useCallback } from "react";
import { setAccessToken, getAccessToken } from "./api";
import { useRouter } from "next/navigation";

export interface AuthUser {
  id: string;
  username: string;
  email: string;
  groups: string[];
  permissions: string[];
}

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  logoutAll: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    async function restoreSession() {
      try {
        const refreshRes = await fetch("/api/v1/auth/refresh", {
          method: "POST",
          credentials: "include",
        });
        if (!refreshRes.ok) return;
        const { access_token } = await refreshRes.json();
        setAccessToken(access_token);

        const meRes = await fetch("/api/v1/auth/me", {
          headers: { Authorization: `Bearer ${access_token}` },
          credentials: "include",
        });
        if (meRes.ok) setUser(await meRes.json());
      } catch {
        // no session
      } finally {
        setIsLoading(false);
      }
    }
    restoreSession();
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await fetch("/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
        credentials: "include",
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error((data as { message?: string }).message ?? "Invalid credentials");
      }
      const { access_token } = await res.json();
      setAccessToken(access_token);

      const meRes = await fetch("/api/v1/auth/me", {
        headers: { Authorization: `Bearer ${access_token}` },
        credentials: "include",
      });
      if (meRes.ok) setUser(await meRes.json());
      router.push("/");
    },
    [router]
  );

  const logout = useCallback(async () => {
    const token = getAccessToken();
    await fetch("/api/v1/auth/logout", {
      method: "POST",
      credentials: "include",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    }).catch(() => {});
    setAccessToken(null);
    setUser(null);
    router.push("/login");
  }, [router]);

  const logoutAll = useCallback(async () => {
    const token = getAccessToken();
    await fetch("/api/v1/auth/logout-all", {
      method: "POST",
      credentials: "include",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    }).catch(() => {});
    setAccessToken(null);
    setUser(null);
    router.push("/login");
  }, [router]);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, logout, logoutAll }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
