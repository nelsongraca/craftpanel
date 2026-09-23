"use client";

import {useEffect, useState, useSyncExternalStore} from "react";
import Image from "next/image";
import Link from "next/link";
import {usePathname} from "next/navigation";
import {
    AlertTriangle,
    Bell,
    ChevronDown,
    KeyRound,
    LayoutDashboard,
    LogOut,
    type LucideIcon,
    Menu,
    Monitor,
    Network,
    Server,
    Settings,
    UserCircle,
    Users,
} from "lucide-react";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuGroup,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {fetchBrandingConfig, getBrandingSnapshot, logoUrl, subscribeBranding} from "@/lib/config";
import {useHealth} from "@/lib/hooks/useHealth";

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
        items: [{label: "Dashboard", href: "/", icon: LayoutDashboard}],
    },
    {
        title: "Servers",
        items: [
            {label: "All Servers", href: "/servers", icon: Server},
            {label: "Networks", href: "/networks", icon: Network, permission: "network.view"},
        ],
    },
    {
        title: "Infrastructure",
        items: [{label: "Nodes", href: "/nodes", icon: Monitor, permission: "system.nodes"}],
    },
    {
        title: "System",
        items: [
            {label: "Alerts", href: "/alerts", icon: Bell, permission: "system.alerts"},
            {label: "Users", href: "/users", icon: Users, permission: "system.users"},
            {label: "Groups", href: "/groups", icon: KeyRound, permission: "system.groups"},
            {label: "Settings", href: "/settings", icon: Settings, permission: "system.settings"},
        ],
    },
];

export default function Shell({children}: {children: React.ReactNode}) {
    const pathname = usePathname();
    const {user, logout} = useAuth();
    const [drawerOpen, setDrawerOpen] = useState(false);
    const health = useHealth();
    const branding = useSyncExternalStore(subscribeBranding, getBrandingSnapshot, () => null);

    const permissions = user?.permissions ?? [];

    useEffect(() => {
        void fetchBrandingConfig();
    }, []);

    // Keep the browser tab title in sync when the app name changes at runtime.
    useEffect(() => {
        if (branding?.appName) document.title = branding.appName;
    }, [branding?.appName]);

    // Close the mobile drawer after navigation, else it covers the page just opened.
    useEffect(() => {
        setDrawerOpen(false);
    }, [pathname]);

    const versionsMatch =
        !!health &&
        health.frontendVersion !== "unknown" &&
        health.masterVersion !== "unknown" &&
        health.frontendVersion === health.masterVersion;

    return (
        <div className="flex min-h-0 flex-1 flex-col">
            {/* Top bar — brand + user menu */}
            <header className="flex h-[48px] shrink-0 items-center justify-between border-b border-border bg-surface px-5">
                {/* Logo + hamburger (hamburger only < md) */}
                <div className="flex items-center gap-3">
                    <button
                        aria-label="Open navigation"
                        onClick={() => setDrawerOpen((o) => !o)}
                        className="text-text-dim transition-colors hover:text-text-primary md:hidden"
                    >
                        <Menu size={20} strokeWidth={2} />
                    </button>
                    <Image
                        src={logoUrl(branding)}
                        alt={`${branding?.appName ?? "CraftPanel"} logo`}
                        width={26}
                        height={26}
                        unoptimized
                        className="shrink-0"
                    />
                    <span className="font-heading text-base font-bold tracking-widest text-accent uppercase">
                        {branding?.appName ?? "CraftPanel"}
                    </span>
                </div>

                {/* User menu */}
                <div className="relative flex justify-end">
                    <DropdownMenu>
                        <DropdownMenuTrigger className="flex items-center gap-1.5 font-heading text-xs font-bold tracking-widest text-text-dim uppercase transition-colors hover:text-text-primary">
                            <span>{user?.username ?? "User"}</span>
                            <ChevronDown size={12} strokeWidth={2.5} />
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="min-w-[180px] border-border bg-surface-higher">
                            <DropdownMenuGroup>
                                <DropdownMenuLabel className="truncate text-text-muted">
                                    {user?.email}
                                </DropdownMenuLabel>
                                <DropdownMenuItem render={<Link href="/account" />} className="text-text-primary">
                                    <UserCircle size={13} strokeWidth={2} />
                                    Account
                                </DropdownMenuItem>
                                <DropdownMenuItem onClick={logout} className="text-text-primary">
                                    <LogOut size={13} strokeWidth={2} />
                                    Sign out
                                </DropdownMenuItem>
                            </DropdownMenuGroup>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            </header>

            {/* Body: sidebar + content */}
            <div className="relative mx-auto flex min-h-0 w-full max-w-[1800px] flex-1">
                {/* Backdrop — only < md, only when drawer open */}
                {drawerOpen && (
                    <div
                        className="fixed inset-0 top-[48px] z-30 bg-bg/70 md:hidden"
                        onClick={() => setDrawerOpen(false)}
                    />
                )}
                {/* Sidebar — static w-[208px] at >= md; off-canvas drawer < md */}
                <aside
                    className={[
                        "flex w-[208px] shrink-0 flex-col overflow-y-auto border-r border-border bg-surface py-3",
                        "max-md:fixed max-md:top-[48px] max-md:bottom-0 max-md:left-0 max-md:z-40 max-md:transition-transform",
                        drawerOpen ? "max-md:translate-x-0" : "max-md:-translate-x-full",
                    ].join(" ")}
                >
                    {sidebarSections.map((section) => {
                        const visibleItems = section.items.filter(
                            (item) => !item.permission || hasPermission(permissions, item.permission),
                        );
                        if (visibleItems.length === 0) return null;
                        return (
                            <div key={section.title} className="mb-2">
                                <p className="px-4 pt-4 pb-1.5 font-heading text-xs font-bold tracking-[0.12em] text-text-muted uppercase">
                                    {section.title}
                                </p>
                                {visibleItems.map((item) => {
                                    const Icon = item.icon;
                                    const isActive =
                                        item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                                    return (
                                        <Link
                                            key={item.label}
                                            href={item.href}
                                            onClick={() => setDrawerOpen(false)}
                                            className={[
                                                "flex items-center gap-2.5 border-l-[3px] py-[6px] pr-4 pl-[13px] font-heading text-sm font-bold tracking-wider uppercase transition-colors",
                                                isActive
                                                    ? "border-accent bg-[var(--accent-subtle)] text-accent"
                                                    : "border-transparent text-text-dim hover:bg-surface-high hover:text-text-primary",
                                            ].join(" ")}
                                        >
                                            <Icon size={13} strokeWidth={2} />
                                            <span>{item.label}</span>
                                        </Link>
                                    );
                                })}
                            </div>
                        );
                    })}
                </aside>

                {/* Content area */}
                <main className="min-h-0 flex-1 overflow-auto bg-bg">{children}</main>
            </div>

            <footer className="flex shrink-0 items-center justify-between gap-3 border-t border-border bg-surface px-4 py-1.5">
                <span className="font-mono text-[10px] text-text-muted">powered by CraftPanel</span>
                <div className="flex items-center gap-3">
                    {health?.versionMismatch && (
                        <span
                            className="flex items-center gap-1 font-mono text-xs text-warning"
                            title="Frontend and master are running different versions"
                        >
                            <AlertTriangle size={12} strokeWidth={2} />
                            version mismatch
                        </span>
                    )}
                    <span className="font-mono text-xs text-text-muted">
                        {versionsMatch
                            ? `version ${health.frontendVersion}`
                            : `frontend ${health?.frontendVersion ?? "…"} · master ${health?.masterVersion ?? "…"}`}
                    </span>
                </div>
            </footer>
        </div>
    );
}
