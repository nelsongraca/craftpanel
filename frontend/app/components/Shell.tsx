"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  Server,
  Network,
  Monitor,
  Bell,
  Settings,
  Users,
  KeyRound,
  type LucideIcon,
} from "lucide-react";

const topNavItems = [
  { label: "Dashboard", href: "/" },
  { label: "Servers", href: "/servers" },
  { label: "Networks", href: "/networks" },
  { label: "Nodes", href: "/nodes" },
  { label: "Alerts", href: "/alerts" },
  { label: "Settings", href: "/settings" },
];

const sidebarSections: {
  title: string;
  items: { label: string; href: string; icon: LucideIcon }[];
}[] = [
  {
    title: "Servers",
    items: [
      { label: "All Servers", href: "/servers", icon: Server },
      { label: "Networks", href: "/networks", icon: Network },
    ],
  },
  {
    title: "Infrastructure",
    items: [{ label: "Nodes", href: "/nodes", icon: Monitor }],
  },
  {
    title: "System",
    items: [
      { label: "Alerts", href: "/alerts", icon: Bell },
      { label: "Users", href: "/users", icon: Users },
      { label: "Groups", href: "/groups", icon: KeyRound },
      { label: "Settings", href: "/settings", icon: Settings },
    ],
  },
];

export default function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <div className="flex flex-col h-full">
      {/* Top Navigation */}
      <header className="flex items-center justify-between bg-surface px-5 py-3 text-text-primary border-b border-border">
        <span className="text-[17px] font-bold font-heading tracking-wide text-accent">
          ⛏ CraftPanel
        </span>
        <nav className="flex gap-5 text-[13px] opacity-85">
          {topNavItems.map((item) => {
            const isActive =
              item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
            return (
              <Link
                key={item.label}
                href={item.href}
                className={
                  isActive
                    ? "opacity-100 font-bold border-b-2 border-accent pb-0.5"
                    : "hover:opacity-100"
                }
              >
                {item.label}
              </Link>
            );
          })}
          <span className="cursor-pointer">admin ▾</span>
        </nav>
      </header>

      {/* Shell: sidebar + content */}
      <div className="flex flex-1">
        {/* Sidebar */}
        <aside className="w-[200px] flex-shrink-0 bg-surface border-r border-border flex flex-col py-4">
          {sidebarSections.map((section) => (
            <div key={section.title}>
              <div className="px-4 pt-3 pb-1 text-[10px] font-bold uppercase tracking-wider text-text-muted">
                {section.title}
              </div>
              {section.items.map((item) => {
                const Icon = item.icon;
                const isActive = pathname.startsWith(item.href);
                return (
                  <Link
                    key={item.label}
                    href={item.href}
                    className={`flex items-center gap-2 px-4 py-[7px] text-[13px] hover:bg-surface-high ${
                      isActive ? "text-accent bg-surface-high" : "text-text-primary"
                    }`}
                  >
                    <Icon size={14} />
                    <span>{item.label}</span>
                  </Link>
                );
              })}
            </div>
          ))}
        </aside>

        {/* Content area */}
        <main className="flex-1 overflow-auto p-6 bg-bg">{children}</main>
      </div>
    </div>
  );
}
