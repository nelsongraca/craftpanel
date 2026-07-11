"use client";

import {type FormEvent, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {useAuth} from "@/lib/auth-context";
import {TextField} from "@/components/ui/form-elements";

export default function LoginPage() {
    const {user, isLoading, login} = useAuth();
    const router = useRouter();
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (!isLoading && user) {
            router.replace("/");
        }
    }, [isLoading, user, router]);

    async function handleSubmit(e: FormEvent) {
        e.preventDefault();
        setError("");
        setSubmitting(true);
        try {
            await login(email, password);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Login failed");
        } finally {
            setSubmitting(false);
        }
    }

    if (isLoading || user) return null;

    return (
        <div className="flex-1 flex items-center justify-center">
            <div className="w-full max-w-sm bg-surface border border-border rounded-lg p-8">
                <div className="text-center mb-8">
                    <h1 className="text-2xl font-bold font-heading tracking-wide text-accent">
                        ⛏ CraftPanel
                    </h1>
                    <p className="text-text-muted text-xs mt-2">Sign in to your account</p>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-xs font-medium text-text-dim mb-1.5">
                            Email
                        </label>
                        <TextField
                            type="email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            surface="surface-high"
                            fieldSize="md"
                            placeholder="you@example.com"
                            required
                            autoComplete="email"
                            autoFocus
                        />
                    </div>

                    <div>
                        <label className="block text-xs font-medium text-text-dim mb-1.5">
                            Password
                        </label>
                        <TextField
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            surface="surface-high"
                            fieldSize="md"
                            placeholder="••••••••"
                            required
                            autoComplete="current-password"
                        />
                    </div>

                    {error && (
                        <p className="text-error text-xs py-1">{error}</p>
                    )}

                    <button
                        type="submit"
                        disabled={submitting}
                        className="w-full bg-accent hover:bg-accent-bright text-bg font-semibold text-sm py-2 rounded transition-colors disabled:opacity-60 disabled:cursor-not-allowed mt-2"
                    >
                        {submitting ? "Signing in…" : "Sign in"}
                    </button>
                </form>
            </div>
        </div>
    );
}
