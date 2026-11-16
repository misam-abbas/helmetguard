// Shown inside the dashboard frame while a page's data loads, so a click always gets an answer.
export default function Loading() {
  return (
    <div role="status" aria-live="polite" className="animate-pulse">
      <span className="sr-only">Loading…</span>
      <div className="mb-5 h-7 w-52 max-w-full rounded-md bg-surface-2" />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="h-20 rounded-lg bg-surface-2" />
        ))}
      </div>
      <div className="mt-4 h-56 rounded-lg bg-surface-2" />
      <div className="mt-4 space-y-2">
        {Array.from({ length: 3 }, (_, index) => (
          <div key={index} className="h-14 rounded-lg bg-surface-2" />
        ))}
      </div>
    </div>
  );
}
