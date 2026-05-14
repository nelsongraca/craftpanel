import Link from "next/link";

const topNavItems = [
  { label: "Dashboard", href: "/", active: true },
  { label: "Servers", href: "/servers" },
  { label: "Networks", href: "/networks" },
  { label: "Nodes", href: "/nodes" },
  { label: "Alerts", href: "/alerts" },
  { label: "Settings", href: "/settings" },
];

const sidebarSections: {
  title: string;
  items: { label: string; href: string; icon: string }[];
}[] = [
  {
    title: "Servers",
    items: [
      { label: "All Servers", href: "/servers", icon: "⬛" },
      { label: "Networks", href: "/networks", icon: "🌐" },
    ],
  },
  {
    title: "Infrastructure",
    items: [{ label: "Nodes", href: "/nodes", icon: "🖥" }],
  },
  {
    title: "System",
    items: [
      { label: "Alerts", href: "/alerts", icon: "🔔" },
      { label: "Users", href: "/users", icon: "👥" },
      { label: "Groups", href: "/groups", icon: "🔑" },
      { label: "Settings", href: "/settings", icon: "⚙️" },
    ],
  },
];

export default function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-col h-full">
      {/* Top Navigation */}
      <header className="flex items-center justify-between bg-surface px-5 py-3 text-text-primary border-b border-border">
        <span className="text-[17px] font-bold font-heading tracking-wide text-accent">
          ⛏ CraftPanel
        </span>
        <nav className="flex gap-5 text-[13px] opacity-85">
          {topNavItems.map((item) => (
            <Link
              key={item.label}
              href={item.href}
              className={
                item.active
                  ? "opacity-100 font-bold border-b-2 border-accent pb-0.5"
                  : "hover:opacity-100"
              }
            >
              {item.label}
            </Link>
          ))}
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
              {section.items.map((item) => (
                <Link
                  key={item.label}
                  href={item.href}
                  className="flex items-center gap-2 px-4 py-[7px] text-[13px] text-text-primary hover:bg-surface-high"
                >
                  <span>{item.icon}</span>
                  <span>{item.label}</span>
                </Link>
              ))}
            </div>
          ))}
        </aside>

        {/* Content area */}
        <main className="flex-1 overflow-auto p-6 bg-bg">{children}</main>
      </div>
    </div>
  );
}
