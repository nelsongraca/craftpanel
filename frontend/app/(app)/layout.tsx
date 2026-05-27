"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import Shell from "@/app/components/Shell";
import { WsProvider } from "@/lib/ws-context";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && !user) {
      router.replace("/login");
    }
  }, [isLoading, user, router]);

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <span className="text-text-muted text-sm">Loading…</span>
      </div>
    );
  }

  if (!user) return null;

  return (
    <WsProvider>
      <Shell>{children}</Shell>
    </WsProvider>
  );
}
