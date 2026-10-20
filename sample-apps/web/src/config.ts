import type { GorssoConfig } from "@gorsso/react";

const env = import.meta.env;

export const institution = {
  slug: (env.VITE_INSTITUTION as string | undefined) ?? "minagri",
  name: (env.VITE_INSTITUTION_NAME as string | undefined) ?? "Ministry",
  app: (env.VITE_APP_NAME as string | undefined) ?? "Sample",
};

export const apiBase = ((env.VITE_API_BASE as string | undefined) ?? "").replace(/\/+$/, "");

export const gorssoConfig: GorssoConfig = {
  mode: "pkce",
  baseUrl: (env.VITE_GORSSO_BASE_URL as string | undefined) ?? "http://localhost:8000",
  clientId: (env.VITE_CLIENT_ID as string | undefined) ?? `${institution.slug}-web`,
  redirectUri: (env.VITE_REDIRECT_URI as string | undefined) ?? `${window.location.origin}/callback`,
  postLogoutRedirectUri: (env.VITE_POST_LOGOUT_REDIRECT_URI as string | undefined) ?? `${window.location.origin}/`,
  scopes: ((env.VITE_SCOPES as string | undefined) ?? "openid profile email").split(/\s+/).filter(Boolean),
};
