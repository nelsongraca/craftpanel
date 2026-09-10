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
    const [appName, setAppName] = useState("CraftPanel");
    const [branding, setBranding] = useState<BrandingConfig | null>(null);

    useEffect(() => {
        fetchBrandingConfig().then((cfg) => {
            setBranding(cfg);
            setAppName(cfg.appName);
        });
    }, []);

    useEffect(() => {
        if (!isLoading && user) {
            router.replace("/");
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
                await verifyTotp(challenge.tempToken, code);
            }
        } catch (err) {
            setError(err instanceof Error ? err.message : "Verification failed");
        } finally {
            setSubmitting(false);
        }
    }

    function toggleRecovery() {
        setChallenge((c) => c ? {...c, recovery: !c.recovery} : c);
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
            <div className="flex-1 flex items-center justify-center">
                <div className="w-full max-w-sm bg-surface border border-border rounded-lg p-8">
                    <div className="text-center mb-8">
                        <div className="flex items-center justify-center gap-3 mb-2">
                            <Image src={logoUrl(branding)} alt={`${appName} logo`} width={36} height={36} unoptimized/>
                            <h1 className="text-2xl font-bold font-heading tracking-wide text-accent">
                                {appName}
                            </h1>
                        </div>
                        <h2 className="text-sm font-heading font-bold uppercase tracking-widest text-text-primary pt-2">
                            Two-factor authentication
                        </h2>
                        <p className="text-text-muted text-xs mt-1.5">
                            {challenge.recovery
                                ? "Enter one of your backup codes. Each code can only be used once."
                                : "Enter the 6-digit code from your authenticator app."}
                        </p>
                    </div>

                    <form onSubmit={handleChallenge} className="space-y-4">
                        <div>
                            <label className="block text-xs font-medium text-text-dim mb-1.5">
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
                                className="font-mono tracking-[0.3em] text-center"
                            />
                        </div>

                        {error && (
                            <p className="text-error text-xs py-1">{error}</p>
                        )}

                        <button
                            type="submit"
                            disabled={submitting || code.trim().length === 0}
                            className="w-full bg-accent hover:bg-accent-bright text-bg font-semibold text-sm py-2 rounded transition-colors disabled:opacity-60 disabled:cursor-not-allowed mt-2"
                        >
                            {submitting
                                ? challenge.recovery ? "Verifying…" : "Verifying…"
                                : challenge.recovery ? "Use recovery code" : "Verify"}
                        </button>

                        <div className="flex items-center justify-between text-xs pt-1">
                            <button type="button" onClick={toggleRecovery} className="text-text-dim hover:text-text-primary transition-colors">
                                {challenge.recovery ? "Use authenticator code" : "Use a recovery code"}
                            </button>
                            <button type="button" onClick={backToCredentials} className="text-text-dim hover:text-text-primary transition-colors">
                                Back
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        );
    }

    return (
        <div className="flex-1 flex items-center justify-center">
            <div className="w-full max-w-sm bg-surface border border-border rounded-lg p-8">
                <div className="text-center mb-8">
                    <div className="flex items-center justify-center gap-3 mb-2">
                        <Image src={logoUrl(branding)} alt={`${appName} logo`} width={36} height={36} unoptimized/>
                        <h1 className="text-2xl font-bold font-heading tracking-wide text-accent">
                            {appName}
                        </h1>
                    </div>
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