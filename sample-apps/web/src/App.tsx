import { RequireAuth, RequireRole, useAuth } from "@gorsso/react";
import { Link, NavLink, Outlet, Route, Routes } from "react-router-dom";
import { institution } from "./config";
import { Callback } from "./pages/Callback";
import { Home } from "./pages/Home";
import { Me } from "./pages/Me";
import { Records } from "./pages/Records";

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="callback" element={<Callback />} />
        {/* Any signed-in user */}
        <Route element={<RequireAuth fallback={<p className="muted">Signing you in…</p>}><Outlet /></RequireAuth>}>
          <Route path="me" element={<Me />} />
        </Route>
        {/* Members of this ministry only (cosmetic — the API enforces the same rule) */}
        <Route
          path="records"
          element={
            <RequireRole
              role={`inst:${institution.slug}:*`}
              fallback={<p className="muted">Signing you in…</p>}
              forbidden={<Forbidden />}
            >
              <Records />
            </RequireRole>
          }
        />
        <Route path="*" element={<p>Not found. <Link to="/">Home</Link></p>} />
      </Route>
    </Routes>
  );
}

function Layout() {
  const { isAuthenticated, session, login, logout, status } = useAuth();
  return (
    <div className="shell">
      <header>
        <div>
          <strong>{institution.app}</strong>
          <span className="muted"> · {institution.name}</span>
        </div>
        <nav>
          <NavLink to="/" end>Home</NavLink>
          <NavLink to="/me">Me</NavLink>
          <NavLink to="/records">Records</NavLink>
        </nav>
        <div>
          {isAuthenticated ? (
            <>
              <span className="muted">{session.username}</span>{" "}
              <button onClick={() => void logout()}>Sign out</button>
            </>
          ) : (
            <button disabled={status === "loading"} onClick={() => void login("/me")}>Sign in with GoRSSO</button>
          )}
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}

function Forbidden() {
  return (
    <section>
      <h2>403 — not for you</h2>
      <p>
        This page needs a role in <code>inst:{institution.slug}:*</code>. Ask your institution admin.
      </p>
    </section>
  );
}
