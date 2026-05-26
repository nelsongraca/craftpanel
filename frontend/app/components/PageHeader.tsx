interface PageHeaderProps {
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
}

export default function PageHeader({ title, subtitle, action }: PageHeaderProps) {
  return (
    <div className="flex items-start justify-between px-6 pt-6 pb-5 border-b border-border">
      <div>
        <h1 className="text-[18px] font-heading font-bold uppercase tracking-wide text-text-primary">
          {title}
        </h1>
        {subtitle && (
          <p className="text-[12px] text-text-muted mt-0.5">{subtitle}</p>
        )}
      </div>
      {action && <div className="shrink-0 ml-4">{action}</div>}
    </div>
  );
}
