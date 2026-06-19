"use client";

import {useEffect, useRef, useState} from "react";
import Link from "next/link";
import {usePathname} from "next/navigation";
import {Bell, ChevronDown, KeyRound, LogOut, type LucideIcon, Monitor, Network, Server, Settings, Users,} from "lucide-react";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";

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
    const [menuOpen, setMenuOpen] = useState(false);
    const menuRef = useRef<HTMLDivElement>(null);

    const permissions = user?.permissions ?? [];

    const closeMenu = (fn: () => void) => () => {
        setMenuOpen(false);
        fn();
    };

    useEffect(() => {
        function handleClickOutside(e: MouseEvent) {
            if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
                setMenuOpen(false);
            }
        }

        if (menuOpen) document.addEventListener("mousedown", handleClickOutside);
        return () => document.removeEventListener("mousedown", handleClickOutside);
    }, [menuOpen]);

    return (
        <div className="flex flex-col flex-1 min-h-0">
            {/* Top bar — brand + user menu */}
            <header className="flex items-center justify-between bg-surface border-b border-border px-5 h-[48px] shrink-0">
                {/* Logo */}
                <span className="text-[15px] font-bold font-heading tracking-widest uppercase text-accent">
          ⛏ CraftPanel
        </span>

                {/* User menu */}
                <div ref={menuRef} className="relative flex justify-end">
                    <button
                        onClick={() => setMenuOpen((o) => !o)}
                        className="flex items-center gap-1.5 text-[12px] font-heading font-bold uppercase tracking-widest text-text-dim hover:text-text-primary transition-colors"
                    >
                        <span>{user?.username ?? "User"}</span>
                        <ChevronDown size={12} strokeWidth={2.5} className={menuOpen ? "rotate-180 transition-transform" : "transition-transform"}/>
                    </button>

                    {menuOpen && (
                        <div className="absolute right-0 top-full mt-2 bg-surface-higher border border-border rounded-md shadow-xl z-50 min-w-[180px] py-1 overflow-hidden">
                            <div className="px-3 py-2 border-b border-border">
                                <p className="text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted truncate">
                                    {user?.email}
                                </p>
                            </div>
                            <button
                                onClick={closeMenu(logout)}
                                className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] hover:bg-surface-high text-text-primary transition-colors"
                            >
                                <LogOut size={13} strokeWidth={2}/>
                                Sign out
                            </button>
                            <button
                                onClick={closeMenu(logoutAll)}
                                className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] hover:bg-surface-high text-text-dim transition-colors"
                            >
                                <LogOut size={13} strokeWidth={2}/>
                                Sign out all sessions
                            </button>
                        </div>
                    )}
                </div>
            </header>

            {/* Body: sidebar + content */}
            <div className="flex flex-1 min-h-0">
                {/* Sidebar */}
                <aside className="w-[208px] shrink-0 bg-surface border-r border-border flex flex-col py-3 overflow-y-auto">
                    {sidebarSections.map((section) => {
                        const visibleItems = section.items.filter(
                            (item) => !item.permission || hasPermission(permissions, item.permission)
                        );
                        if (visibleItems.length === 0) return null;
                        return (
                            <div key={section.title} className="mb-2">
                                <p className="px-4 pt-4 pb-1.5 text-[10px] font-heading font-bold uppercase tracking-[0.12em] text-text-muted">
                                    {section.title}
                                </p>
                                {visibleItems.map((item) => {
                                    const Icon = item.icon;
                                    const isActive = pathname.startsWith(item.href);
                                    return (
                                        <Link
                                            key={item.label}
                                            href={item.href}
                                            className={[
                                                "flex items-center gap-2.5 pl-[13px] pr-4 py-[6px] text-[12px] font-heading font-bold uppercase tracking-wider border-l-[3px] transition-colors",
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
        </div>
    );
}
