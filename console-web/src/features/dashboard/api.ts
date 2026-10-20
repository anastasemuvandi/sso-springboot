/** Dashboard: keys and queries. */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap } from "@/api/client";

export const dashboardKeys = {
  all: ["dashboard"] as const,
  overview: () => [...dashboardKeys.all, "overview"] as const,
  metrics: () => [...dashboardKeys.all, "metrics"] as const,
  sessions: () => [...dashboardKeys.all, "sessions"] as const,
  loginStats: (period: string) => [...dashboardKeys.all, "login-stats", period] as const,
  recentLogins: (limit: number) => [...dashboardKeys.all, "recent-logins", limit] as const,
};

/**
 * GET /dashboard/overview — cached 60 s by the wrapper; `refresh` bypasses
 * that cache (`?refresh=true`).
 */
export function fetchOverview(refresh = false) {
  return unwrap(api.GET("/dashboard/overview", { params: { query: refresh ? { refresh: true } : {} } }));
}

export const overviewQuery = () => queryOptions({ queryKey: dashboardKeys.overview(), queryFn: () => fetchOverview(false) });

export const metricsQuery = () =>
  queryOptions({ queryKey: dashboardKeys.metrics(), queryFn: () => unwrap(api.GET("/dashboard/metrics")) });

export const sessionsInfoQuery = () =>
  queryOptions({ queryKey: dashboardKeys.sessions(), queryFn: () => unwrap(api.GET("/dashboard/sessions")) });

export const loginStatsQuery = (period = "day") =>
  queryOptions({
    queryKey: dashboardKeys.loginStats(period),
    queryFn: () => unwrap(api.GET("/dashboard/logins/stats", { params: { query: { period } } })),
  });

export const recentLoginsQuery = (limit = 10) =>
  queryOptions({
    queryKey: dashboardKeys.recentLogins(limit),
    queryFn: () => unwrap(api.GET("/dashboard/logins/recent", { params: { query: { max_items: limit } } })),
  });
