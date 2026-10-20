import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { codeChallengeS256 } from "../pkce";
import { PkceAuthClient } from "../pkceClient";
import { MemoryTransactionStore } from "../transaction";
import type { GorssoConfig } from "../types";
import { accessToken, fakeJwt, jsonResponse, now } from "./helpers";

const ORIGIN = window.location.origin;
const REDIRECT = `${ORIGIN}/callback`;

function setup(over: Partial<GorssoConfig> = {}) {
  const navigate = vi.fn<(url: string) => void>();
  const onRedirectCallback = vi.fn<(to: string) => void>();
  const fetchMock = vi.fn<typeof fetch>();
  const store = new MemoryTransactionStore();
  const client = new PkceAuthClient({
    mode: "pkce",
    baseUrl: "http://localhost:8000",
    clientId: "minagri-kubaka-web",
    redirectUri: REDIRECT,
    navigate,
    onRedirectCallback,
    fetch: fetchMock,
    transactionStore: store,
    ...over,
  });
  return { client, navigate, onRedirectCallback, fetchMock, store };
}

/** Run login() and return the authorize request's params. */
async function startLogin(client: PkceAuthClient, navigate: ReturnType<typeof vi.fn>, returnTo = "/records") {
  await client.login(returnTo);
  expect(navigate).toHaveBeenCalledTimes(1);
  return new URL(navigate.mock.calls[0]![0] as string).searchParams;
}

function tokenResponse(nonce: string, extra: Record<string, unknown> = {}) {
  return jsonResponse({
    access_token: accessToken(),
    refresh_token: "rt-1",
    id_token: fakeJwt({ nonce, aud: "minagri-kubaka-web", exp: now() + 300, name: "Jean Doe", email: "j@gov.rw" }),
    expires_in: 300,
    token_type: "Bearer",
    ...extra,
  });
}

describe("PkceAuthClient", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/");
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => vi.useRealTimers());

  it("starts unauthenticated when the URL is not a callback", async () => {
    const { client } = setup();
    await client.init();
    expect(client.getState().status).toBe("unauthenticated");
  });

  it("login sends S256 challenge, state and nonce to the wrapper's /oauth2/authorize", async () => {
    const { client, navigate, store } = setup();
    const q = await startLogin(client, navigate);
    expect(navigate.mock.calls[0]![0]).toMatch(/^http:\/\/localhost:8000\/oauth2\/authorize\?/);
    expect(q.get("code_challenge_method")).toBe("S256");
    expect(q.get("client_id")).toBe("minagri-kubaka-web");
    expect(q.get("redirect_uri")).toBe(REDIRECT);
    const state = q.get("state")!;
    const tx = store.take(state)!;
    expect(tx.nonce).toBe(q.get("nonce"));
    expect(await codeChallengeS256(tx.codeVerifier)).toBe(q.get("code_challenge"));
    expect(tx.returnTo).toBe("/records");
  });

  it("a full callback exchanges the code with the verifier and keeps tokens in memory only", async () => {
    const { client, navigate, fetchMock, store, onRedirectCallback } = setup();
    const q = await startLogin(client, navigate);
    const state = q.get("state")!;
    const nonce = q.get("nonce")!;
    // peek at the verifier without consuming the transaction
    const tx = store.take(state)!;
    store.save(state, tx);
    fetchMock.mockResolvedValueOnce(tokenResponse(nonce));

    window.history.replaceState({}, "", `/callback?code=abc&state=${state}`);
    await client.init();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://localhost:8000/oauth2/token");
    const form = new URLSearchParams(init!.body as string);
    expect(form.get("grant_type")).toBe("authorization_code");
    expect(form.get("code")).toBe("abc");
    expect(form.get("code_verifier")).toBe(tx.codeVerifier);
    expect(form.get("client_id")).toBe("minagri-kubaka-web");
    expect(form.has("client_secret")).toBe(false);

    const s = client.getState();
    expect(s.status).toBe("authenticated");
    expect(s.session).toMatchObject({
      username: "jdoe",
      name: "Jean Doe",
      joinKey: "1199080012345678",
      institutionSlug: "minagri",
      roles: ["inst:minagri:staff", "offline_access"],
      clientRoles: ["viewer"],
    });
    expect(onRedirectCallback).toHaveBeenCalledWith("/records");
    // memory only
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain("rt-1");
    expect(await client.getAccessToken()).toBe(client.peekTokens()!.accessToken);
  });

  it("rejects a callback whose state was never issued (CSRF) without calling /token", async () => {
    const { client, fetchMock } = setup();
    window.history.replaceState({}, "", "/callback?code=abc&state=forged");
    await client.init();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(client.getState().status).toBe("error");
    expect(client.getState().error?.code).toBe("state_mismatch");
  });

  it("a state is single-use: replaying the callback fails", async () => {
    const first = setup();
    const q = await startLogin(first.client, first.navigate);
    first.fetchMock.mockResolvedValue(tokenResponse(q.get("nonce")!));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q.get("state")}`);
    await first.client.init();
    expect(first.client.getState().status).toBe("authenticated");

    // same store, new page load
    const second = new PkceAuthClient({
      mode: "pkce",
      baseUrl: "http://localhost:8000",
      clientId: "minagri-kubaka-web",
      redirectUri: REDIRECT,
      fetch: first.fetchMock,
      transactionStore: first.store,
      onRedirectCallback: () => {},
    });
    await second.init();
    expect(second.getState().error?.code).toBe("state_mismatch");
    expect(first.fetchMock).toHaveBeenCalledTimes(1);
  });

  it("rejects an id_token whose nonce does not match", async () => {
    const { client, navigate, fetchMock } = setup();
    const q = await startLogin(client, navigate);
    fetchMock.mockResolvedValueOnce(tokenResponse("someone-elses-nonce"));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q.get("state")}`);
    await client.init();
    expect(client.getState().status).toBe("error");
    expect(client.getState().error?.code).toBe("nonce_mismatch");
    expect(client.peekTokens()).toBeNull();
  });

  it("surfaces IdP errors and gateway gate errors (e.g. join_key_missing)", async () => {
    const a = setup();
    const q1 = await startLogin(a.client, a.navigate);
    window.history.replaceState({}, "", `/callback?error=access_denied&error_description=nope&state=${q1.get("state")}`);
    await a.client.init();
    expect(a.client.getState().error?.code).toBe("access_denied");

    const b = setup();
    const q2 = await startLogin(b.client, b.navigate);
    b.fetchMock.mockResolvedValueOnce(jsonResponse({ error: "join_key_missing", message: "no NID" }, 403));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q2.get("state")}`);
    await b.client.init();
    expect(b.client.getState().error).toMatchObject({ code: "join_key_missing", status: 403 });
  });

  it("handles the callback once even if init runs twice (StrictMode)", async () => {
    const { client, navigate, fetchMock } = setup();
    const q = await startLogin(client, navigate);
    fetchMock.mockResolvedValue(tokenResponse(q.get("nonce")!));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q.get("state")}`);
    await Promise.all([client.init(), client.init()]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(client.getState().status).toBe("authenticated");
  });

  it("refresh rotates the refresh token; a failed refresh signs out", async () => {
    const { client, navigate, fetchMock } = setup({ refreshLeewaySeconds: 30 });
    const q = await startLogin(client, navigate);
    fetchMock.mockResolvedValueOnce(tokenResponse(q.get("nonce")!, { expires_in: 10 }));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q.get("state")}`);
    await client.init();

    // expires_in 10 < leeway 30 → getAccessToken refreshes
    fetchMock.mockResolvedValueOnce(jsonResponse({ access_token: accessToken({ v: 2 }), refresh_token: "rt-2", expires_in: 300 }));
    const t = await client.getAccessToken();
    const form = new URLSearchParams(fetchMock.mock.calls[1]![1]!.body as string);
    expect(form.get("grant_type")).toBe("refresh_token");
    expect(form.get("refresh_token")).toBe("rt-1");
    expect(client.peekTokens()!.refreshToken).toBe("rt-2");
    expect(client.peekTokens()!.idToken).toBeDefined(); // kept from the login
    expect(t).toBe(client.peekTokens()!.accessToken);

    fetchMock.mockResolvedValueOnce(jsonResponse({ error: "invalid_grant" }, 400));
    await client.refresh();
    expect(client.getState().status).toBe("error");
    expect(client.getState().error?.code).toBe("invalid_grant");
    expect(await client.getAccessToken()).toBeNull();
  });

  it("logout clears memory and goes to the wrapper's /oauth2/logout with client_id and id_token_hint", async () => {
    const { client, navigate, fetchMock } = setup();
    const q = await startLogin(client, navigate);
    fetchMock.mockResolvedValueOnce(tokenResponse(q.get("nonce")!));
    window.history.replaceState({}, "", `/callback?code=abc&state=${q.get("state")}`);
    await client.init();
    const idToken = client.peekTokens()!.idToken!;

    await client.logout();
    expect(client.peekTokens()).toBeNull();
    expect(client.getState().status).toBe("unauthenticated");
    const u = new URL(navigate.mock.calls[1]![0] as string);
    expect(u.origin + u.pathname).toBe("http://localhost:8000/oauth2/logout");
    expect(u.searchParams.get("client_id")).toBe("minagri-kubaka-web");
    expect(u.searchParams.get("id_token_hint")).toBe(idToken);
    expect(u.searchParams.get("post_logout_redirect_uri")).toBe(`${ORIGIN}/`);
  });

  it("refuses to start without its required config", () => {
    expect(() => new PkceAuthClient({ mode: "pkce" })).toThrow(/baseUrl/);
  });
});
