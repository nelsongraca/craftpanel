export default function Home() {
  return (
    <div>
      <div className="flex items-start justify-between mb-5">
        <div>
          <h1 className="text-[20px] font-bold font-heading tracking-wide">Dashboard</h1>
          <p className="text-[12px] text-text-muted mt-1">Overview of the entire platform</p>
        </div>
      </div>
      <div className="border-2 border-dashed border-border rounded-md py-10 px-8 text-center text-text-muted text-[13px]">
        Dashboard content area — see docs for screen mockups
      </div>
    </div>
  );
}
