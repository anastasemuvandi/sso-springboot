import { useAuth, useJoinKey } from "@gorsso/react";
import { useApi } from "../api";

interface MeResponse {
  username: string;
  name?: string;
  email?: string;
  join_key?: string;
  join_column?: string;
  institution?: string;
  institution_roles: string[];
  client_roles: string[];
}

export function Me() {
  const { session } = useAuth();
  const joinKey = useJoinKey();
  const me = useApi<MeResponse>("/api/me");
  return (
    <section>
      <h2>Me</h2>
      <h3>From the token (client side)</h3>
      <dl>
        <dt>Username</dt><dd>{session.username}</dd>
        <dt>join_key</dt><dd>{joinKey ?? <em>none</em>}</dd>
        <dt>Realm roles</dt><dd>{(session.roles ?? []).join(", ") || <em>none</em>}</dd>
        <dt>Client roles</dt><dd>{(session.clientRoles ?? []).join(", ") || <em>none</em>}</dd>
      </dl>
      <h3>From the API (<code>GET /api/me</code>, verified server side)</h3>
      {me.state === "loading" && <p className="muted">Loading…</p>}
      {me.state === "error" && <p role="alert">API error {me.status}: {me.error}</p>}
      {me.state === "ok" && (
        <dl>
          <dt>Username</dt><dd>{me.data.username}</dd>
          <dt>join_key ({me.data.join_column ?? "?"})</dt><dd>{me.data.join_key ?? <em>none</em>}</dd>
          <dt>Institution</dt><dd>{me.data.institution ?? <em>unknown</em>}</dd>
          <dt>Institution roles</dt><dd>{me.data.institution_roles.join(", ") || <em>none</em>}</dd>
          <dt>Client roles</dt><dd>{me.data.client_roles.join(", ") || <em>none</em>}</dd>
        </dl>
      )}
    </section>
  );
}
