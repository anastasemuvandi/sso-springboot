// Stand-in for the BFF + wrapper, for the Playwright gate.
//
// `vite preview` serves the built console and proxies /api, /bff, /oauth2,
// /login here (CONSOLE_BFF_URL). State lives in cookies so parallel tests do
// not interfere:
//   stub_session = platform | institution   (absent = not signed in)
//   stub_expired = 1                         (the BFF's token refresh failed:
//                                             /api/** → 401, /bff/session → unauthenticated)
//
// Endpoints:
//   GET  /bff/session                → the session JSON the SPA reads
//   GET  /bff/login?returnTo=…       → a stub "login" page (the real BFF starts PKCE)
//   GET  /bff/login/complete?as=…&returnTo=…  → "signs in": sets stub_session, clears stub_expired, 302 returnTo
//   POST /bff/logout                 → clears the session, 302 /
//   *    /api/**                     → canned wrapper responses (401 when expired / signed out)
import http from "node:http";

const PORT = Number(process.env.STUB_PORT ?? 3999);

const PERSONAS = {
  platform: {
    authenticated: true,
    username: "admin@risa.gov.rw",
    name: "Platform Admin",
    roles: ["super_admin"],
    platformAdmin: true,
    institutionAdmin: false,
    institutionSlug: null,
  },
  institution: {
    authenticated: true,
    username: "hr@minagri.gov.rw",
    name: "MINAGRI HR",
    roles: ["inst:minagri:hr-admin"],
    platformAdmin: false,
    institutionAdmin: true,
    institutionSlug: "minagri",
  },
};

function cookies(req) {
  const out = {};
  for (const part of (req.headers.cookie ?? "").split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k) out[k] = decodeURIComponent(v.join("="));
  }
  return out;
}

function send(res, status, body, headers = {}) {
  const isString = typeof body === "string";
  res.writeHead(status, { "Content-Type": isString ? "text/html; charset=utf-8" : "application/json", ...headers });
  res.end(isString ? body : JSON.stringify(body));
}

const envelope = (error, message, details = {}) => ({ error, message, details });

const zeroOverview = () => ({
  institution: null,
  generated_at: new Date().toISOString(),
  members: 0,
  leavers: { available: true, error: null, applications_checked: true, total: 0, truncated: false, items: [] },
  password_setup: { available: true, error: null, counts: { total: 0, sent: 0, failed: 0, not_configured: 0, unknown: 0 }, items: [] },
  logins: { available: true, error: null, logins_24h: 3, logins_7d: 12, failed_24h: 0, failed_7d: 1, people_24h: 2, people_7d: 4, most_failures_7d: [], locked_now: [], truncated: false, active_sessions: 2 },
  access: { available: true, error: null, items: [] },
});

// ---------------------------------------------------------------------------
// Institution persona (minagri): directory, apps and provisioning.
// ---------------------------------------------------------------------------
const CAPS = ["view-members", "manage-members", "manage-membership", "map-roles", "view-clients", "manage-clients"];

/** 60 employees (two directory pages of 50): every 10th has no email and no account, #7 is a leaver. */
const EMPLOYEES = Array.from({ length: 60 }, (_, i) => {
  const n = String(i + 1).padStart(4, "0");
  const linked = i % 3 === 0;
  return {
    id: `emp-${n}`,
    employee_number: `E${n}`,
    first_name: `Employee${n}`,
    last_name: "Minagri",
    email: i % 10 === 9 && !linked ? null : `e${n}@minagri.gov.rw`,
    phone: null,
    position: "Officer",
    status: i === 6 ? "terminated" : "active",
    institution_code: "MINAGRI",
    institution_slug: "minagri",
    keycloak_user_id: linked ? `user-${n}` : null,
    match_state: linked ? "matched" : "unmatched",
  };
});

const INSTITUTION_APPS = [
  {
    id: "app-1",
    clientId: "minagri-kubaka",
    name: "Kubaka",
    manageable: true,
    cross_cutting: false,
    owned: true,
    scope: { kind: "institution", owner_institution: "minagri", cross_cutting: false, all_institutions: false, institutions: [] },
    join_column: { client_id: "minagri-kubaka", join_column: "email", claim: "join_key", user_source: "email", explicit: true },
    mfa_required: true,
    mfa_applies: true,
    mfa_mode: "all",
  },
];

function employeePage(url) {
  const q = (url.searchParams.get("search") ?? "").toLowerCase();
  const account = url.searchParams.get("account");
  const matches = (e) => !q || `${e.employee_number} ${e.first_name} ${e.last_name} ${e.email ?? ""}`.toLowerCase().includes(q);
  const filtered = EMPLOYEES.filter(matches);
  const byAccount = filtered.filter((e) =>
    account === "linked" ? e.keycloak_user_id : account === "unlinked" ? !e.keycloak_user_id : account === "ambiguous" ? e.match_state === "ambiguous" : true,
  );
  const limit = Number(url.searchParams.get("limit") ?? 50);
  const offset = Number(url.searchParams.get("offset") ?? 0);
  const linked = filtered.filter((e) => e.keycloak_user_id).length;
  return {
    institution: "minagri",
    scoped: true,
    total: byAccount.length,
    limit,
    offset,
    summary: { total: filtered.length, linked, unlinked: filtered.length - linked, ambiguous: 0, unmapped: 0, no_email: filtered.filter((e) => !e.email && !e.keycloak_user_id).length },
    items: byAccount.slice(offset, offset + limit),
  };
}

function provisionReport(body) {
  const numbers = Array.isArray(body?.employee_numbers) ? body.employee_numbers : [];
  const clients = Array.isArray(body?.client_ids) ? body.client_ids : [];
  const rows = numbers.map((n) => {
    const e = EMPLOYEES.find((x) => x.employee_number === n);
    const existing = Boolean(e?.keycloak_user_id);
    return {
      employee_number: n,
      name: e ? `${e.first_name} ${e.last_name}` : n,
      username: existing ? `${n.toLowerCase()}` : (e?.email ?? null),
      action: existing ? "link" : "create",
      account: existing ? "existing" : "created",
      outcome: existing ? "linked" : "created",
      user_id: existing ? e.keycloak_user_id : body?.dry_run ? null : `new-${n}`,
      clients_granted: clients,
      clients_skipped: [],
      clients_denied: [],
      password_email: body?.dry_run ? null : existing ? "not_needed" : "sent",
      welcome_email: body?.dry_run ? null : "sent",
    };
  });
  const created = rows.filter((r) => r.account === "created").length;
  return {
    institution: "minagri",
    dry_run: Boolean(body?.dry_run),
    clients: clients.map((c) => ({ client_id: c, join_column: "email", employee_field: "email", configured: true })),
    totals: {
      selected: rows.length,
      created,
      linked: rows.length - created,
      skipped: 0,
      failed: 0,
      grants: rows.length * clients.length,
      already_held: 0,
      accounts_existing: rows.length - created,
      denials: 0,
      clients_denying_everyone: [],
    },
    employees: rows,
  };
}

function institutionApi(url, method, body) {
  const p = url.pathname.slice(4).replace(/\/+$/, "");
  if (method === "POST" && p === "/institution-admin/employees/provision") return [200, provisionReport(body)];
  if (method !== "GET") return null;
  switch (p) {
    case "/institution-admin/me":
      return [200, { institution: "minagri", administered: ["minagri"], is_platform_admin: false, actor: "hr@minagri.gov.rw", capabilities: CAPS }];
    case "/institution-admin/permissions":
      return [200, { institution: "minagri", templates: ["hr-admin"], capabilities: CAPS, members: { group: "minagri", include_subgroups: true }, roles: { allow: ["inst:minagri:*"], deny: [] }, clients: { allow: ["minagri-*"], deny: [] } }];
    case "/institution-admin/overview":
      return [200, { ...zeroOverview(), institution: "minagri" }];
    case "/institution-admin/apps":
      return [200, INSTITUTION_APPS];
    case "/employees":
      return [200, employeePage(url)];
    default:
      return null;
  }
}

function api(path, method) {
  if (method !== "GET") return [200, {}];
  const p = path.replace(/\/+$/, "") || "/";
  switch (p) {
    case "/dashboard/overview":
      return [200, zeroOverview()];
    case "/dashboard/metrics":
      return [200, { total_users: 5, total_clients: 3, total_agencies: 2, active_sessions: 2, logins_today: 3, new_users_this_week: 1 }];
    case "/dashboard/sessions":
      return [200, { total_sessions: 2, client_sessions: [] }];
    case "/dashboard/logins/recent":
      return [200, []];
    case "/dashboard/logins/stats":
      return [200, { period: "day", data: [], total_logins: 0, unique_users: 0 }];
    case "/dashboard/events":
      return [200, []];
    case "/employees":
      return [200, { total: 0, limit: 1, offset: 0, items: [], summary: { total: 0, linked: 0, unlinked: 0, unmapped: 0, ambiguous: 0, no_email: 0 } }];
    case "/client/clients":
      return [200, []];
    case "/realm/mfa":
      return [200, { mode: "all", raw_mode: "all", enforced: true, effective: true, raw: null, browser_flow: "browser-totp", flow_ready: true, first_enabled_at: null, exempt_count: 0 }];
    case "/audit/events":
      return [200, { total: 0, limit: 8, offset: 0, items: [] }];
    case "/users":
      return [200, { users: [], total: 0, page: 1, pageSize: 20, totalPages: 0 }];
    case "/users/count":
      return [200, { count: 0 }];
    case "/agency":
    case "/roles/realm-roles":
    case "/roles/clients":
      return [200, []];
    default:
      return [404, envelope("not_found", `stub: no canned response for GET ${p}`)];
  }
}

const loginPage = (returnTo) => `<!doctype html><html><head><title>Stub BFF login</title></head><body>
<h1>Stub BFF login</h1>
<p data-testid="return-to">${returnTo.replace(/[<>&"]/g, "")}</p>
<a data-testid="login-platform" href="/bff/login/complete?as=platform&returnTo=${encodeURIComponent(returnTo)}">Sign in as platform admin</a>
<a data-testid="login-institution" href="/bff/login/complete?as=institution&returnTo=${encodeURIComponent(returnTo)}">Sign in as institution admin</a>
</body></html>`;

function readBody(req) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (c) => (data += c));
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : undefined);
      } catch {
        resolve(undefined);
      }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://stub");
  const c = cookies(req);
  const persona = PERSONAS[c.stub_session];
  const signedIn = Boolean(persona) && c.stub_expired !== "1";

  if (url.pathname === "/bff/session") return send(res, 200, signedIn ? persona : { authenticated: false });

  if (url.pathname === "/bff/login") return send(res, 200, loginPage(url.searchParams.get("returnTo") ?? "/"));

  if (url.pathname === "/bff/login/complete") {
    const as = url.searchParams.get("as") === "institution" ? "institution" : "platform";
    const returnTo = url.searchParams.get("returnTo") ?? "/";
    const safe = returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/";
    return send(res, 302, "", {
      Location: safe,
      "Set-Cookie": [`stub_session=${as}; Path=/; SameSite=Lax`, "stub_expired=; Path=/; Max-Age=0", "XSRF-TOKEN=stub-xsrf; Path=/; SameSite=Lax"],
    });
  }

  if (url.pathname === "/bff/logout" && req.method === "POST") {
    return send(res, 302, "", { Location: "/", "Set-Cookie": ["stub_session=; Path=/; Max-Age=0"] });
  }

  if (url.pathname === "/api" || url.pathname.startsWith("/api/")) {
    if (!signedIn) return send(res, 401, envelope("unauthorized", "The session has expired."));
    if (req.method !== "GET" && req.headers["x-xsrf-token"] !== c["XSRF-TOKEN"]) {
      return send(res, 403, envelope("csrf_failed", "Missing or invalid X-XSRF-TOKEN."));
    }
    if (c.stub_session === "institution") {
      const body = req.method === "GET" ? undefined : await readBody(req);
      const answer = institutionApi(url, req.method ?? "GET", body);
      if (answer) return send(res, answer[0], answer[1]);
    }
    const [status, body] = api(url.pathname.slice(4), req.method ?? "GET");
    return send(res, status, body);
  }

  send(res, 404, envelope("not_found", `stub: ${url.pathname}`));
});

server.listen(PORT, "127.0.0.1", () => console.log(`stub BFF+wrapper on http://127.0.0.1:${PORT}`));
