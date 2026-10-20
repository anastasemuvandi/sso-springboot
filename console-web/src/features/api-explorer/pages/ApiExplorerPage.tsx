/**
 * /api-explorer — Swagger UI over the wrapper's springdoc document, fetched
 * through the BFF (`/api/v3/api-docs`). Lazy-loaded: swagger-ui is large and
 * only this route pulls it in.
 *
 * "Try it out" requests go through the BFF too (`/api/...`), so they run as
 * the signed-in admin with the BFF session cookie and CSRF header — the SPA
 * still never sees a token.
 */
import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";

import { readCookie, XSRF_COOKIE, XSRF_HEADER, MUTATING_METHODS } from "@/api/csrf";
import { API_BASE } from "@/api/client";
import { PageHeader } from "@/components/common/PageHeader";
import { Loading } from "@/components/common/QueryState";

const SwaggerUI = lazy(async () => {
  const [mod] = await Promise.all([import("swagger-ui-react"), import("swagger-ui-react/swagger-ui.css")]);
  return { default: mod.default };
});

export const OPENAPI_URL = `${API_BASE}/v3/api-docs`;

interface SwaggerRequest {
  url: string;
  method?: string;
  headers: Record<string, string>;
  credentials?: string;
}

/** Route "Try it out" calls through the BFF and add the CSRF header. */
export function bffRequestInterceptor(req: SwaggerRequest): SwaggerRequest {
  try {
    const u = new URL(req.url, window.location.origin);
    if (u.origin === window.location.origin && !u.pathname.startsWith(`${API_BASE}/`) && !u.pathname.startsWith("/bff/")) {
      req.url = `${API_BASE}${u.pathname}${u.search}`;
    }
  } catch {
    /* leave as is */
  }
  req.credentials = "include";
  if (MUTATING_METHODS.has((req.method ?? "GET").toUpperCase())) {
    const token = readCookie(XSRF_COOKIE);
    if (token) req.headers[XSRF_HEADER] = token;
  }
  return req;
}

export function Component() {
  const { t } = useTranslation();
  return (
    <div className="space-y-6">
      <PageHeader title={t("apiExplorer.title")} description={t("apiExplorer.description", { url: OPENAPI_URL })} />
      <div className="rounded-xl border bg-white p-2 text-black" data-testid="api-explorer">
        <Suspense fallback={<Loading label={t("apiExplorer.loading")} />}>
          <SwaggerUI url={OPENAPI_URL} requestInterceptor={bffRequestInterceptor as never} deepLinking docExpansion="none" tryItOutEnabled={false} />
        </Suspense>
      </div>
    </div>
  );
}
