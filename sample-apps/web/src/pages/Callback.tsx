import { useAuth } from "@gorsso/react";

/** The provider finishes the code exchange; this page only shows progress or the failure. */
export function Callback() {
  const { status, error, login } = useAuth();
  if (status === "error") {
    return (
      <section>
        <h2>Sign-in failed</h2>
        <p>
          <code>{error?.code}</code> — {error?.message}
        </p>
        <button onClick={() => void login("/me")}>Try again</button>
      </section>
    );
  }
  return <p className="muted">Completing sign-in…</p>;
}
