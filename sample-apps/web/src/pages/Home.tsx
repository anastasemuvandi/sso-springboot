import { useAuth } from "@gorsso/react";
import { useEffect, useState } from "react";
import { apiBase, gorssoConfig, institution } from "../config";

export function Home() {
  const { isAuthenticated, session } = useAuth();
  const [info, setInfo] = useState<{ display_name?: string } | null>(null);
  useEffect(() => {
    fetch(`${apiBase}/api/public/info`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setInfo)
      .catch(() => setInfo(null));
  }, []);
  return (
    <section>
      <h1>{institution.name}</h1>
      <p>
        Demo app <code>{gorssoConfig.clientId}</code> signing in through the GoRSSO wrapper at{" "}
        <code>{gorssoConfig.baseUrl}</code> (authorization code + PKCE S256, tokens in memory only).
      </p>
      <p className="muted">API: {info?.display_name ? `connected (${info.display_name})` : "not reachable"}</p>
      {isAuthenticated && <p>Signed in as <strong>{session.username}</strong>.</p>}
    </section>
  );
}
