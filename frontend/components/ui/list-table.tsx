import type {ReactNode} from "react";

// Shared list-table primitives.
//
// Standard convention (adopted from the Networks / Users lists):
//   - card wrapper: bg-surface border border-border rounded-md overflow-hidden
//   - header cells: text-xs font-heading font-bold uppercase tracking-widest text-text-muted
//   - body rows:    border-b border-border/50 hover:bg-surface-high/40
//   - action buttons: icon-only, p-1.5 rounded hover:bg-surface-higher
//   - mobile fallback: md:hidden divide-y divide-border cards
// Every list page uses these so the "list of things" look stays coherent.

export function ListTh({
                           children,
                           align = "left",
                           className = "",
                       }: {
    children?: ReactNode;
    align?: "left" | "right";
    className?: string;
}) {
    const justify = align === "right" ? "text-right" : "text-left";
    return (
        <th className={`px-4 py-3 text-xs font-heading font-bold uppercase tracking-widest text-text-muted ${justify} ${className}`}>
            {children}
        </th>
    );
}

export function ListTd({
                           children,
                           className = "",
                           firstCol = false,
                       }: {
    children: ReactNode;
    className?: string;
    firstCol?: boolean;
}) {
    const pad = firstCol ? "px-5 py-3" : "px-4 py-3";
    return <td className={`${pad} ${className}`}>{children}</td>;
}

export function ListActions({children}: { children: ReactNode }) {
    return (
        <td className="px-4 py-3">
            <div className="flex items-center gap-1 justify-end">{children}</div>
        </td>
    );
}

export function IconActionButton({
                                     icon,
                                     label,
                                     onClick,
                                     danger = false,
                                     disabled = false,
                                 }: {
    icon: ReactNode;
    label: string;
    onClick: () => void;
    danger?: boolean;
    disabled?: boolean;
}) {
    const tone = danger
        ? "hover:text-error text-text-muted"
        : "text-text-muted hover:text-text-primary";
    return (
        <button
            onClick={onClick}
            disabled={disabled}
            title={label}
            className={`p-1.5 rounded hover:bg-surface-higher ${tone} transition-colors disabled:opacity-30 disabled:cursor-not-allowed`}
        >
            {icon}
        </button>
    );
}

export function ListBody({children}: { children: ReactNode }) {
    return <tbody>{children}</tbody>;
}

export function ListEmpty({message}: { message: string }) {
    return (
        <tr>
            <td colSpan={99} className="py-8 text-center text-xs text-text-muted">
                {message}
            </td>
        </tr>
    );
}
