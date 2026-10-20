/** Audit trail (hash-chained audit service, through the wrapper's proxy) and Keycloak activity: keys, queries. */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import type { Activity24h, AuditEventFilters, AuditEventPage, AuditIntegrityReport, AuditRetentionPolicy, KeycloakEvent } from "./types";

export const auditKeys = {
  all: ["audit"] as const,
  events: (filters: AuditEventFilters) => [...auditKeys.all, "events", filters] as const,
  integrity: (window: { from_date?: string; to_date?: string }) => [...auditKeys.all, "integrity", window] as const,
  retention: () => [...auditKeys.all, "retention"] as const,
  keycloakEvents: (params: { event_types?: string; max_events?: number }) => [...auditKeys.all, "keycloak-events", params] as const,
  activity24h: () => [...auditKeys.all, "activity-24h"] as const,
  loginStats: (period: string) => [...auditKeys.all, "login-stats", period] as const,
};

/** GET /audit/events */
export const auditEventsQuery = (filters: AuditEventFilters = {}) =>
  queryOptions({
    queryKey: auditKeys.events(filters),
    queryFn: () => unwrapAs<AuditEventPage>(api.GET("/audit/events", { params: { query: filters } })),
  });

/** GET /audit/integrity/verify — re-walks the hash chain over an optional window. */
export const integrityQuery = (window: { from_date?: string; to_date?: string } = {}) =>
  queryOptions({
    queryKey: auditKeys.integrity(window),
    queryFn: () => unwrapAs<AuditIntegrityReport>(api.GET("/audit/integrity/verify", { params: { query: window } })),
    staleTime: 0,
  });

/** GET /audit/retention/policies */
export const retentionQuery = () =>
  queryOptions({
    queryKey: auditKeys.retention(),
    queryFn: () => unwrapAs<AuditRetentionPolicy[]>(api.GET("/audit/retention/policies")),
  });

/** GET /dashboard/events — Keycloak's raw realm events (not the audit trail). */
export const keycloakEventsQuery = (params: { event_types?: string; max_events?: number } = {}) =>
  queryOptions({
    queryKey: auditKeys.keycloakEvents(params),
    queryFn: () => unwrapAs<KeycloakEvent[]>(api.GET("/dashboard/events", { params: { query: params } })),
  });

/** GET /dashboard/activity/24h */
export const activity24hQuery = () =>
  queryOptions({
    queryKey: auditKeys.activity24h(),
    queryFn: () => unwrapAs<Activity24h>(api.GET("/dashboard/activity/24h")),
  });

/** GET /dashboard/logins/stats */
export const loginStatsQuery = (period: string) =>
  queryOptions({
    queryKey: auditKeys.loginStats(period),
    queryFn: () => unwrap(api.GET("/dashboard/logins/stats", { params: { query: { period } } })),
  });
