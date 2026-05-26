import PageHeader from "@/app/components/PageHeader";

export default function Home() {
  return (
    <div>
      <PageHeader
        title="Dashboard"
        subtitle="Overview of the entire platform"
      />
      <div className="p-6">
        <div className="border-2 border-dashed border-border rounded-md py-10 px-8 text-center text-text-muted text-[13px]">
          Dashboard content area — see docs for screen mockups
        </div>
      </div>
    </div>
  );
}
