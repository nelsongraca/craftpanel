"use client";

import {createContext, useCallback, useContext, useEffect, useState} from "react";
import {setAccessToken} from "./client";
import {initFingerprint, getCachedFingerprint} from "./fingerprint";
import {
    authChangePassword,
    authLogin,
    authLogout,
    authLogoutAll,
    authMe,
    authRefresh,
    authTotpRecovery,
    authTotpVerify,
} from "@/lib/generated";
import type {MeResponse} from "@/lib/generated";
import {useRouter} from "next/navigation";

export interface AuthUser {
    id: string;
    username: string;
    email: string;
    groups: string[];
    permissions: string[];
    server_permissions: Record<string, string[]>;
    totp_enabled: boolean;
    must_change_password: boolean;
}

function toAuthUser(me: MeResponse): AuthUser {
    return {
        id: me.id,
        username: me.username,
        email: me.email,
        groups: me.groups,
        permissions: me.permissions,
        server_permissions: me.server_permissions,
        totp_enabled: me.totp_enabled,
        must_change_password: me.must_change_password ?? false,
    };
}

export interface LoginOutcome {
    requiresTotp: boolean;
    tempToken: string | null;
}

interface AuthContextValue {
    user: AuthUser | null;
    isLoading: boolean;
    login: (email: string, password: string) => Promise<LoginOutcome>;
    verifyTotp: (tempToken: string, code: string, trustDevice?: boolean) => Promise<void>;
    verifyRecovery: (tempToken: string, code: string) => Promise<void>;
    logout: () => Promise<void>;
    logoutAll: () => Promise<boolean>;
    changePassword: (oldPassword: string, newPassword: string) => Promise<void>;
    forceChangePassword: (newPassword: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({children}: { children: React.ReactNode }) {
    const [user, setUser] = useState<AuthUser | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();

    useEffect(() => {
        void initFingerprint();

        async function restoreSession() {
            try {
                const {data: refreshData} = await authRefresh();
                if (!refreshData) return;
                setAccessToken(refreshData.access_token ?? null);

                const {data: me} = await authMe();
                if (me) setUser(toAuthUser(me));
            } catch {
                // no session
            } finally {
                setIsLoading(false);
            }
        }

        void restoreSession();
    }, []);

    const finishAuth = useCallback(async () => {
        const {data: me} = await authMe();
        if (me) {
            setUser(toAuthUser(me));
            router.push(me.must_change_password ? "/force-password-change" : "/");
        }
    }, [router]);

    const login = useCallback(
        async (email: string, password: string): Promise<LoginOutcome> => {
            const deviceFingerprint = getCachedFingerprint()
            const {data, error} = await authLogin({body: {email, password, device_fingerprint: deviceFingerprint ?? undefined}});
            if (error) throw new Error(error.message ?? "Invalid credentials");
            if (data?.requires_totp) {
                return {requiresTotp: true, tempToken: data.temp_token ?? null};
            }
            setAccessToken(data!.access_token ?? null);
            await finishAuth();
            return {requiresTotp: false, tempToken: null};
        },
        [finishAuth]
    );

    const verifyTotp = useCallback(
        async (tempToken: string, code: string, trustDevice: boolean = false) => {
            const deviceFingerprint = getCachedFingerprint()
            const {data, error} = await authTotpVerify({
                body: {
                    temp_token: tempToken,
                    code,
                    trust_device: trustDevice,
                    device_fingerprint: deviceFingerprint ?? undefined
                }
            });
            if (error) throw new Error(error.message ?? "Invalid verification code");
            setAccessToken(data!.access_token ?? null);
            await finishAuth();
        },
        [finishAuth]
    );

    const verifyRecovery = useCallback(
        async (tempToken: string, code: string) => {
            const {data, error} = await authTotpRecovery({body: {temp_token: tempToken, code}});
            if (error) throw new Error(error.message ?? "Invalid recovery code");
            setAccessToken(data!.access_token ?? null);
            await finishAuth();
        },
        [finishAuth]
    );

    const logout = useCallback(async () => {
        await authLogout().catch(() => {
        });
        setAccessToken(null);
        setUser(null);
        router.push("/login");
    }, [router]);

    const logoutAll = useCallback(async () => {
        const {error} = await authLogoutAll();
        return !error;
    }, []);

    const changePassword = useCallback(async (oldPassword: string, newPassword: string) => {
        const {error} = await authChangePassword({body: {old_password: oldPassword, new_password: newPassword}});
        if (error) throw new Error(error.message ?? "Failed to change password");
        const {data: me} = await authMe();
        if (me) setUser(toAuthUser(me));
    }, []);

    const forceChangePassword = useCallback(async (newPassword: string) => {
        const {error} = await authChangePassword({body: {old_password: "", new_password: newPassword}});
        if (error) throw new Error(error.message ?? "Failed to change password");
        const {data: me} = await authMe();
        if (me) {
            setUser(toAuthUser(me));
            router.push("/");
        }
    }, [router]);

    return (
        <AuthContext.Provider value={{user, isLoading, login, verifyTotp, verifyRecovery, logout, logoutAll, changePassword, forceChangePassword}}>
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error("useAuth must be used within AuthProvider");
    return ctx;
}
