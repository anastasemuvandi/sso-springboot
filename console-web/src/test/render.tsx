import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider, type RouteObject } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";

import { Providers } from "@/app/Providers";
import { sessionKeys, type Session } from "@/auth/session";
import { platformAdmin } from "./server";

export function testQueryClient(session: Session = platformAdmin) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity }, mutations: { retry: false } },
  });
  qc.setQueryData(sessionKeys.all, session);
  return qc;
}

/**
 * Render a page inside the app providers and a memory router.
 * `path` is the route pattern (e.g. "/users/:id"), `route` the URL to open.
 */
export function renderPage(
  element: ReactElement,
  { route = "/", path = "/", extraRoutes = [], session }: { route?: string; path?: string; extraRoutes?: RouteObject[]; session?: Session } = {},
) {
  const client = testQueryClient(session);
  const router = createMemoryRouter([{ path, element }, ...extraRoutes, { path: "*", element: <div data-testid="other-route" /> }], {
    initialEntries: [route],
    future: { v7_relativeSplatPath: true, v7_fetcherPersist: true, v7_normalizeFormMethod: true, v7_partialHydration: true, v7_skipActionErrorRevalidation: true },
  });
  const utils = render(
    <Providers client={client}>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </Providers>,
  );
  return { ...utils, client, router };
}
