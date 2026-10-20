import { useApi } from "../api";

interface RecordsResponse {
  institution: string;
  display_name: string;
  records: { id: string; title: string; kind: string }[];
}

export function Records() {
  const res = useApi<RecordsResponse>("/api/records");
  if (res.state === "loading") return <p className="muted">Loading…</p>;
  if (res.state === "error") return <p role="alert">API error {res.status}: {res.error}</p>;
  return (
    <section>
      <h2>Records — {res.data.display_name}</h2>
      <table>
        <thead>
          <tr><th>ID</th><th>Title</th><th>Kind</th></tr>
        </thead>
        <tbody>
          {res.data.records.map((r) => (
            <tr key={r.id}><td>{r.id}</td><td>{r.title}</td><td>{r.kind}</td></tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
