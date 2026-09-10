"use client";

import {useEffect} from "react";
import {usePathname, useRouter} from "next/navigation";
import {useAuth} from "@/lib/auth-context";
import Shell from "@/app/components/Shell";
import {WsProvider} from "@/lib/ws-context";
import ForcePasswordChange from "@/app/(app)/force-password-change/page";

const FORCE_PASSWORD_CHANGE_PATH = "/force-password-change";

export default function AppLayout({children}: { children: React.ReactNode }) {
    const {user, isLoading} = useAuth();
    const router = useRouter();
    const pathname = usePathname();

    useEffect(() => {
        if (!isLoading && !user) {
            router.replace("/login");
        } else if (!isLoading && user?.must_change_password && pathname !== FORCE_PASSWORD_CHANGE_PATH) {
            router.replace(FORCE_PASSWORD_CHANGE_PATH);
        }
    }, [isLoading, user, router, pathname]);

    if (isLoading) {
        return (
            <div className="flex-1 flex items-center justify-center">
                <span className="text-text-muted text-sm">Loading…</span>
            </div>
        );
    }

    if (!user) return null;

    if (user.must_change_password) {
        return <ForcePasswordChange/>;
    }

    return (
        <WsProvider>
            <Shell>{children}</Shell>
        </WsProvider>
    );
}
