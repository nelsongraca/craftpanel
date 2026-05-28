"use client";

import {createContext, useCallback, useContext, useEffect, useState} from "react";
import {setAccessToken} from "./client";
import {authLogin, authLogout, authLogoutAll, authMe, authRefresh} from "@/lib/generated";
import {useRouter} from "next/navigation";

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

export function AuthProvider({children}: { children: React.ReactNode }) {
    const [user, setUser] = useState<AuthUser | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();

    useEffect(() => {
        async function restoreSession() {
            try {
                const {data: refreshData} = await authRefresh();
                if (!refreshData) return;
                setAccessToken(refreshData.access_token);

                const {data: me} = await authMe();
                if (me) setUser(me);
            } catch {
                // no session
            } finally {
                setIsLoading(false);
            }
        }

        void restoreSession();
    }, []);

    const login = useCallback(
        async (email: string, password: string) => {
            const {data, error} = await authLogin({body: {email, password}});
            if (error) throw new Error(error.message ?? "Invalid credentials");
            setAccessToken(data!.access_token);

            const {data: me} = await authMe();
            if (me) setUser(me);
            router.push("/");
        },
        [router]
    );

    const logout = useCallback(async () => {
        await authLogout().catch(() => {
        });
        setAccessToken(null);
        setUser(null);
        router.push("/login");
    }, [router]);

    const logoutAll = useCallback(async () => {
        await authLogoutAll().catch(() => {
        });
        setAccessToken(null);
        setUser(null);
        router.push("/login");
    }, [router]);

    return (
        <AuthContext.Provider value={{user, isLoading, login, logout, logoutAll}}>
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error("useAuth must be used within AuthProvider");
    return ctx;
}
