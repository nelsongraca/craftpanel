interface PageHeaderProps {
    title: string;
    subtitle?: string;
    action?: React.ReactNode;
}

export default function PageHeader({title, subtitle, action}: PageHeaderProps) {
    return (
        <div className="flex flex-col gap-3 border-b border-border px-4 pt-4 pb-3 sm:flex-row sm:items-start sm:justify-between sm:gap-0">
            <div className="min-w-0">
                <h1 className="font-heading text-[18px] font-bold tracking-wide text-text-primary uppercase">
                    {title}
                </h1>
                {subtitle && <p className="mt-0.5 text-xs text-text-muted">{subtitle}</p>}
            </div>
            {action && <div className="shrink-0 sm:ml-4">{action}</div>}
        </div>
    );
}
