"use client";

import {useEffect, useState} from "react";
import Link from "next/link";
import {usePathname} from "next/navigation";
import {AlertTriangle, Bell, ChevronDown, KeyRound, LayoutDashboard, LogOut, type LucideIcon, Menu, Monitor, Network, Server, Settings, Users,} from "lucide-react";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger} from "@/components/ui/dropdown-menu";

interface HealthInfo {
    frontendVersion: string;
    masterVersion: string;
    versionMismatch: boolean;
}

interface SidebarItem {
    label: string;
    href: string;
    icon: LucideIcon;
    permission?: string;
}

interface SidebarSection {
    title: string;
    items: SidebarItem[];
}

const sidebarSections: SidebarSection[] = [
    {
        title: "Overview",
        items: [
            {label: "Dashboard", href: "/", icon: LayoutDashboard},
        ],
    },
    {
        title: "Servers",
        items: [
            {label: "All Servers", href: "/servers", icon: Server},
            {label: "Networks", href: "/networks", icon: Network},
        ],
    },
    {
        title: "Infrastructure",
        items: [
            {label: "Nodes", href: "/nodes", icon: Monitor, permission: "system.nodes"},
        ],
    },
    {
        title: "System",
        items: [
            {label: "Alerts", href: "/alerts", icon: Bell},
            {label: "Users", href: "/users", icon: Users, permission: "system.users"},
            {label: "Groups", href: "/groups", icon: KeyRound, permission: "system.users"},
            {label: "Settings", href: "/settings", icon: Settings, permission: "system.settings"},
        ],
    },
];

export default function Shell({children}: { children: React.ReactNode }) {
    const pathname = usePathname();
    const {user, logout, logoutAll} = useAuth();
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [health, setHealth] = useState<HealthInfo | null>(null);

    const permissions = user?.permissions ?? [];

    useEffect(() => {
        fetch("/healthz")
            .then((res) => res.json())
            .then(setHealth)
            .catch(() => setHealth(null));
    }, []);

    // Close the mobile drawer after navigation, else it covers the page just opened.
    useEffect(() => {
        setDrawerOpen(false);
    }, [pathname]);

    return (
        <div className="flex flex-col flex-1 min-h-0">
            {/* Top bar — brand + user menu */}
            <header className="flex items-center justify-between bg-surface border-b border-border px-5 h-[48px] shrink-0">
                {/* Logo + hamburger (hamburger only < md) */}
                <div className="flex items-center gap-3">
                    <button
                        aria-label="Open navigation"
                        onClick={() => setDrawerOpen((o) => !o)}
                        className="md:hidden text-text-dim hover:text-text-primary transition-colors"
                    >
                        <Menu size={20} strokeWidth={2}/>
                    </button>
                    <span className="text-base font-bold font-heading tracking-widest uppercase text-accent">
                        ⛏ CraftPanel
                    </span>
                </div>

                {/* User menu */}
                <div className="relative flex justify-end">
                    <DropdownMenu>
                        <DropdownMenuTrigger className="flex items-center gap-1.5 text-xs font-heading font-bold uppercase tracking-widest text-text-dim hover:text-text-primary transition-colors">
                            <span>{user?.username ?? "User"}</span>
                            <ChevronDown size={12} strokeWidth={2.5}/>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="min-w-[180px] bg-surface-higher border-border">
                            <DropdownMenuLabel className="text-text-muted truncate">
                                {user?.email}
                            </DropdownMenuLabel>
                            <DropdownMenuGroup>
                                <DropdownMenuItem onClick={logout} className="text-text-primary">
                                    <LogOut size={13} strokeWidth={2}/>
                                    Sign out
                                </DropdownMenuItem>
                                <DropdownMenuItem onClick={logoutAll} className="text-text-dim">
                                    <LogOut size={13} strokeWidth={2}/>
                                    Sign out all sessions
                                </DropdownMenuItem>
                            </DropdownMenuGroup>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            </header>

            {/* Body: sidebar + content */}
            <div className="flex flex-1 min-h-0 relative max-w-[1800px] w-full mx-auto">
                {/* Backdrop — only < md, only when drawer open */}
                {drawerOpen && (
                    <div
                        className="md:hidden fixed inset-0 top-[48px] z-30 bg-bg/70"
                        onClick={() => setDrawerOpen(false)}
                    />
                )}
                {/* Sidebar — static w-[208px] at >= md; off-canvas drawer < md */}
                <aside
                    className={[
                        "w-[208px] shrink-0 bg-surface border-r border-border flex flex-col py-3 overflow-y-auto",
                        "max-md:fixed max-md:top-[48px] max-md:bottom-0 max-md:left-0 max-md:z-40 max-md:transition-transform",
                        drawerOpen ? "max-md:translate-x-0" : "max-md:-translate-x-full",
                    ].join(" ")}
                >
                    {sidebarSections.map((section) => {
                        const visibleItems = section.items.filter(
                            (item) => !item.permission || hasPermission(permissions, item.permission)
                        );
                        if (visibleItems.length === 0) return null;
                        return (
                            <div key={section.title} className="mb-2">
                                <p className="px-4 pt-4 pb-1.5 text-xs font-heading font-bold uppercase tracking-[0.12em] text-text-muted">
                                    {section.title}
                                </p>
                                {visibleItems.map((item) => {
                                    const Icon = item.icon;
                                    const isActive = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                                    return (
                                        <Link
                                            key={item.label}
                                            href={item.href}
                                            onClick={() => setDrawerOpen(false)}
                                            className={[
                                                "flex items-center gap-2.5 pl-[13px] pr-4 py-[6px] text-sm font-heading font-bold uppercase tracking-wider border-l-[3px] transition-colors",
                                                isActive
                                                    ? "border-accent bg-[var(--accent-subtle)] text-accent"
                                                    : "border-transparent text-text-dim hover:bg-surface-high hover:text-text-primary",
                                            ].join(" ")}
                                        >
                                            <Icon size={13} strokeWidth={2}/>
                                            <span>{item.label}</span>
                                        </Link>
                                    );
                                })}
                            </div>
                        );
                    })}
                </aside>

                {/* Content area */}
                <main className="flex-1 overflow-auto bg-bg">
                    {children}
                </main>
            </div>

            <footer className="shrink-0 px-4 py-1.5 border-t border-border bg-surface flex items-center justify-center gap-3">
                {health?.versionMismatch && (
                    <span className="flex items-center gap-1 text-xs font-mono text-warning" title="Frontend and master are running different versions">
                        <AlertTriangle size={12} strokeWidth={2}/>
                        version mismatch
                    </span>
                )}
                <span className="text-xs font-mono text-text-muted">
                    frontend {health?.frontendVersion ?? "…"} · master {health?.masterVersion ?? "…"}
                </span>
            </footer>
        </div>
    );
}
