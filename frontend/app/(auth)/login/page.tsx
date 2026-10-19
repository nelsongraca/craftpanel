"use client";

import {useEffect, useState} from "react";
import Image from "next/image";
import {useRouter} from "next/navigation";
import {useAuth} from "@/lib/auth-context";
import {fetchBrandingConfig, logoUrl, type BrandingConfig} from "@/lib/config";
import {TextField} from "@/components/ui/form-elements";

interface Challenge {
    tempToken: string;
    recovery: boolean;
}

export default function LoginPage() {
    const {user, isLoading, login, verifyTotp, verifyRecovery} = useAuth();
    const router = useRouter();
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [challenge, setChallenge] = useState<Challenge | null>(null);
    const [code, setCode] = useState("");
    const [trustDevice, setTrustDevice] = useState(false);
    const [branding, setBranding] = useState<BrandingConfig | null>(null);

    useEffect(() => {
        fetchBrandingConfig().then((cfg) => {
            setBranding(cfg);
        });
    }, []);

    useEffect(() => {
        if (!isLoading && user) {
            router.replace(user.must_change_password ? "/force-password-change" : "/");
        }
    }, [isLoading, user, router]);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        setSubmitting(true);
        try {
            const outcome = await login(email, password);
            if (outcome.requiresTotp && outcome.tempToken) {
                setCode("");
                setChallenge({tempToken: outcome.tempToken, recovery: false});
            }
        } catch (err) {
            setError(err instanceof Error ? err.message : "Login failed");
        } finally {
            setSubmitting(false);
        }
    }

    async function handleChallenge(e: React.FormEvent) {
        e.preventDefault();
        if (!challenge) return;
        setError("");
        setSubmitting(true);
        try {
            if (challenge.recovery) {
                await verifyRecovery(challenge.tempToken, code);
            } else {
                await verifyTotp(challenge.tempToken, code, trustDevice);
            }
        } catch (err) {
            setError(err instanceof Error ? err.message : "Verification failed");
        } finally {
            setSubmitting(false);
        }
    }

    function toggleRecovery() {
        setChallenge((c) => (c ? {...c, recovery: !c.recovery} : c));
        setCode("");
        setError("");
    }

    function backToCredentials() {
        setChallenge(null);
        setCode("");
        setError("");
    }

    if (isLoading || user) return null;

    if (challenge) {
        return (
            <div className="flex flex-1 items-center justify-center">
                <div className="w-full max-w-sm rounded-lg border border-border bg-surface p-8">
                    <div className="mb-8 text-center">
                        <div className="mb-2 flex items-center justify-center gap-3">
                            <Image
                                src={logoUrl(branding)}
                                alt={`${branding?.appName ?? "CraftPanel"} logo`}
                                width={36}
                                height={36}
                                unoptimized
                            />
                            <h1 className="font-heading text-2xl font-bold tracking-wide text-accent">
                                {branding?.appName ?? "CraftPanel"}
                            </h1>
                        </div>
                        <h2 className="pt-2 font-heading text-sm font-bold tracking-widest text-text-primary uppercase">
                            Two-factor authentication
                        </h2>
                        <p className="mt-1.5 text-xs text-text-muted">
                            {challenge.recovery
                                ? "Enter one of your backup codes. Each code can only be used once."
                                : "Enter the 6-digit code from your authenticator app."}
                        </p>
                    </div>

                    <form onSubmit={handleChallenge} className="space-y-4">
                        <div>
                            <label className="mb-1.5 block text-xs font-medium text-text-dim">
                                {challenge.recovery ? "Recovery code" : "Verification code"}
                            </label>
                            <TextField
                                type="text"
                                inputMode="numeric"
                                value={code}
                                onChange={(e) => setCode(e.target.value)}
                                surface="surface-high"
                                fieldSize="md"
                                placeholder={challenge.recovery ? "XXXX-XXXX" : "123456"}
                                autoComplete="one-time-code"
                                autoFocus
                                className="text-center font-mono tracking-[0.3em]"
                            />
                        </div>

                        {error && <p className="py-1 text-xs text-error">{error}</p>}

                        <label className="flex cursor-pointer items-center gap-2 text-xs text-text-dim">
                            <input
                                type="checkbox"
                                checked={trustDevice}
                                onChange={(e) => setTrustDevice(e.target.checked)}
                                className="h-3.5 w-3.5 rounded border-border bg-surface-high accent-accent"
                            />
                            Trust this device for 30 days
                        </label>

                        <button
                            type="submit"
                            disabled={submitting || code.trim().length === 0}
                            className="mt-2 w-full rounded bg-accent py-2 text-sm font-semibold text-on-accent transition-colors hover:bg-accent-bright disabled:cursor-not-allowed disabled:opacity-60"
                        >
                            {submitting
                                ? challenge.recovery
                                    ? "Verifying…"
                                    : "Verifying…"
                                : challenge.recovery
                                  ? "Use recovery code"
                                  : "Verify"}
                        </button>

                        <div className="flex items-center justify-between pt-1 text-xs">
                            <button
                                type="button"
                                onClick={toggleRecovery}
                                className="text-text-dim transition-colors hover:text-text-primary"
                            >
                                {challenge.recovery ? "Use authenticator code" : "Use a recovery code"}
                            </button>
                            <button
                                type="button"
                                onClick={backToCredentials}
                                className="text-text-dim transition-colors hover:text-text-primary"
                            >
                                Back
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        );
    }

    return (
        <div className="flex flex-1 items-center justify-center">
            <div className="w-full max-w-sm rounded-lg border border-border bg-surface p-8">
                <div className="mb-8 text-center">
                    <div className="mb-2 flex items-center justify-center gap-3">
                        <Image
                            src={logoUrl(branding)}
                            alt={`${branding?.appName ?? "CraftPanel"} logo`}
                            width={36}
                            height={36}
                            unoptimized
                        />
                        <h1 className="font-heading text-2xl font-bold tracking-wide text-accent">
                            {branding?.appName ?? "CraftPanel"}
                        </h1>
                    </div>
                    <p className="mt-2 text-xs text-text-muted">Sign in to your account</p>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="mb-1.5 block text-xs font-medium text-text-dim">Email</label>
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
                        <label className="mb-1.5 block text-xs font-medium text-text-dim">Password</label>
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

                    {error && <p className="py-1 text-xs text-error">{error}</p>}

                    <button
                        type="submit"
                        disabled={submitting}
                        className="mt-2 w-full rounded bg-accent py-2 text-sm font-semibold text-on-accent transition-colors hover:bg-accent-bright disabled:cursor-not-allowed disabled:opacity-60"
                    >
                        {submitting ? "Signing in…" : "Sign in"}
                    </button>
                </form>
            </div>
        </div>
    );
}
