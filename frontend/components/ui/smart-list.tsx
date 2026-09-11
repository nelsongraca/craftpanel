import type {MouseEvent, ReactNode} from "react";
import {Fragment} from "react";
import {ListTh, ListTd, ListActions} from "./list-table";
import {Skeleton} from "./skeleton";

// Shared list module.
//
// Captures the list-of-things convention every resource page follows:
//   - card wrapper: bg-surface border border-border rounded-md overflow-hidden
//   - desktop table: hidden md:table (headers ListTh, body rows hover:bg-surface-high/40)
//   - mobile fallback: md:hidden divide-y divide-border cards
//
// Mobile cards are DERIVED from the column defs by default: the first column
// (or the one flagged `title`) becomes the card title, the remaining visible
// columns render as label:value rows, and `actions` trails at the bottom.
// Pages with a genuinely different card layout (servers, nodes) pass a
// `mobileCard` renderer instead.

export interface SmartListColumn<T> {
    key: string;
    header: ReactNode;
    render: (item: T) => ReactNode;
    align?: "left" | "right";
    className?: string;
    headerClassName?: string;
    onHeaderClick?: () => void;
    label?: string;
    title?: boolean;
    hiddenOnMobile?: boolean;
}

export interface SmartListProps<T> {
    items: T[];
    columns: SmartListColumn<T>[];
    keyFor: (item: T) => string;
    loading?: boolean;
    skeletonRows?: number;
    loadingText?: ReactNode;
    empty?: ReactNode;
    actions?: (item: T) => ReactNode;
    actionsHeader?: ReactNode;
    onRowClick?: (item: T) => void;
    mobileCard?: (item: T) => ReactNode;
    header?: ReactNode;
    className?: string;
}

export function SmartList<T>({
                                 items,
                                 columns,
                                 keyFor,
                                 loading = false,
                                 skeletonRows,
                                 loadingText = "Loading…",
                                 empty = "No items.",
                                 actions,
                                 actionsHeader,
                                 onRowClick,
                                 mobileCard,
                                 header,
                                 className = "",
                             }: SmartListProps<T>) {
    const wrapperClass = `bg-surface border border-border rounded-md overflow-hidden ${className}`;
    const colSpan = columns.length + (actions ? 1 : 0);

    const titleCol = columns.find((c) => c.title && !c.hiddenOnMobile)
        ?? columns.find((c) => !c.hiddenOnMobile);
    const metaCols = titleCol ? columns.filter((c) => c !== titleCol && !c.hiddenOnMobile) : [];

    function stopCell(e: MouseEvent) {
        e.stopPropagation();
    }

    function renderDerivedCard(item: T) {
        if (!titleCol) return null;
        const renderedActions = actions?.(item);
        return (
            <Fragment key={keyFor(item)}>
                <div
                    onClick={onRowClick ? () => onRowClick(item) : undefined}
                    className={`p-3 ${onRowClick ? "cursor-pointer active:bg-surface-high transition-colors" : ""}`}
                >
                    <div className="min-w-0 text-sm font-heading font-bold text-text-primary truncate">
                        {titleCol.render(item)}
                    </div>
                    <div className="mt-2 space-y-1">
                        {metaCols.map((col) => (
                            <div key={col.key} className="flex items-baseline justify-between gap-2">
                                <span className="text-xs text-text-muted">
                                    {col.label ?? (typeof col.header === "string" ? col.header : col.key)}
                                </span>
                                <span className="font-mono text-xs text-text-dim truncate text-right">
                                    {col.render(item)}
                                </span>
                            </div>
                        ))}
                    </div>
                    {renderedActions && (
                        <div className="mt-2.5 flex justify-end" onClick={stopCell}>
                            {renderedActions}
                        </div>
                    )}
                </div>
            </Fragment>
        );
    }

    const showList = !loading && items.length > 0;
    const showEmpty = !loading && items.length === 0;

    return (
        <div className={wrapperClass}>
            {header && <div className="px-4 pt-4 pb-3 border-b border-border">{header}</div>}

            {loading && (
                <>
                    {skeletonRows && skeletonRows > 0 && (
                        <div className="hidden md:block">
                            <table className="w-full text-xs">
                                <thead>
                                <tr className="border-b border-border">
                                    <th className="px-4 py-3 text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                                        &nbsp;
                                    </th>
                                </tr>
                                </thead>
                                <tbody>
                                {Array.from({length: skeletonRows}).map((_, i) => (
                                    <tr key={i} className="border-b border-border/50">
                                        <td colSpan={colSpan} className="px-5 py-3">
                                            <Skeleton className="h-4 w-full bg-surface-higher"/>
                                        </td>
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                    {loadingText && (
                        <p className={`px-4 py-3 text-xs text-text-muted ${skeletonRows ? "md:hidden" : ""}`}>
                            {loadingText}
                        </p>
                    )}
                </>
            )}

            {showList && (
                <div className="hidden md:block overflow-x-auto">
                    <table className="w-full text-xs">
                        <thead>
                        <tr className="border-b border-border">
                            {columns.map((col) => (
                                <ListTh
                                    key={col.key}
                                    align={col.align}
                                    className={col.headerClassName}
                                    onClick={col.onHeaderClick}
                                >
                                    {col.header}
                                </ListTh>
                            ))}
                            {actions && <ListTh align="right">{actionsHeader}</ListTh>}
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => (
                            <tr
                                key={keyFor(item)}
                                onClick={onRowClick ? () => onRowClick(item) : undefined}
                                className={`border-b border-border/50 hover:bg-surface-high/40 ${onRowClick ? "cursor-pointer group transition-colors" : ""}`}
                            >
                                {columns.map((col, i) => (
                                    <ListTd
                                        key={col.key}
                                        firstCol={i === 0}
                                        className={`${col.className ?? ""} ${col.align === "right" ? "text-right" : ""}`}
                                    >
                                        {col.render(item)}
                                    </ListTd>
                                ))}
                                {actions && (
                                    <ListActions>
                                        <div onClick={stopCell}>{actions(item)}</div>
                                    </ListActions>
                                )}
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}

            {showEmpty && (
                <p className="px-4 py-8 text-center text-xs text-text-muted">{empty}</p>
            )}

            {showList && (
                <div className="md:hidden divide-y divide-border">
                    {items.map((item) =>
                        mobileCard
                            ? <Fragment key={keyFor(item)}>{mobileCard(item)}</Fragment>
                            : renderDerivedCard(item)
                    )}
                </div>
            )}
        </div>
    );
}