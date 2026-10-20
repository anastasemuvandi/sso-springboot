/**
 * Every route in the console. Pages are lazy modules exporting `Component`
 * (React Router `lazy`), so adding a page = add a file under
 * `src/features/<feature>/pages/` and one entry here. See README.
 */
import { createBrowserRouter, type RouteObject } from "react-router-dom";
import type { QueryClient } from "@tanstack/react-query";

import { ConsoleLayout } from "@/components/shell/ConsoleLayout";
import { useSession } from "@/auth/session";
import { RouteErrorPage } from "@/features/system/pages/RouteErrorPage";
import { NotFoundPage } from "@/features/system/pages/NotFoundPage";
import { InstitutionLayout } from "@/features/institution/components/InstitutionLayout";
import { guards } from "./guards";

function AnyAreaLayout() {
  const s = useSession();
  return <ConsoleLayout area={s.platformAdmin ? "platform" : "institution"} />;
}

export function buildRoutes(qc: QueryClient): RouteObject[] {
  const g = guards(qc);
  return [
    {
      path: "/",
      errorElement: <RouteErrorPage />,
      children: [
        { index: true, loader: g.home, element: null },
        { path: "no-access", loader: g.authenticated, lazy: () => import("@/features/system/pages/NoAccessPage") },

        // ---------------- Platform area ----------------
        {
          id: "platform",
          loader: g.platform,
          element: <ConsoleLayout area="platform" />,
          errorElement: <RouteErrorPage />,
          children: [
            {
              errorElement: <RouteErrorPage />,
              children: [
                { path: "dashboard", lazy: () => import("@/features/dashboard/pages/DashboardPage") },
                { path: "clients", lazy: () => import("@/features/clients/pages/ClientsListPage") },
                { path: "clients/new", lazy: () => import("@/features/clients/pages/ClientNewPage") },
                { path: "clients/join-keys", lazy: () => import("@/features/clients/pages/JoinKeysPage") },
                { path: "clients/:id", lazy: () => import("@/features/clients/pages/ClientDetailPage") },
                { path: "users", lazy: () => import("@/features/users/pages/UsersListPage") },
                { path: "users/new", lazy: () => import("@/features/users/pages/UserNewPage") },
                { path: "users/:id", lazy: () => import("@/features/users/pages/UserDetailPage") },
                { path: "agencies", lazy: () => import("@/features/agencies/pages/AgenciesListPage") },
                { path: "agencies/new", lazy: () => import("@/features/agencies/pages/AgencyNewPage") },
                { path: "agencies/:id", lazy: () => import("@/features/agencies/pages/AgencyDetailPage") },
                { path: "roles", lazy: () => import("@/features/roles/pages/RolesPage") },
                { path: "employees", lazy: () => import("@/features/employees/pages/EmployeesPage") },
                { path: "audit", lazy: () => import("@/features/audit/pages/AuditPage") },
                { path: "system-audit", lazy: () => import("@/features/audit/pages/SystemAuditPage") },
                { path: "settings", lazy: () => import("@/features/settings/pages/SettingsPage") },
              ],
            },
          ],
        },

        // ---------------- API explorer (any console admin) ----------------
        {
          loader: g.authenticated,
          element: <AnyAreaLayout />,
          errorElement: <RouteErrorPage />,
          children: [{ path: "api-explorer", lazy: () => import("@/features/api-explorer/pages/ApiExplorerPage") }],
        },

        // ---------------- Institution area (delegated admins) ----------------
        // Placeholders; built in src/features/institution/** (see README).
        {
          id: "institution",
          path: "institution",
          loader: g.institution,
          element: <InstitutionLayout />,
          errorElement: <RouteErrorPage />,
          children: [
            {
              errorElement: <RouteErrorPage />,
              children: [
                { index: true, lazy: () => import("@/features/institution/pages/OverviewPage") },
                { path: "staff", lazy: () => import("@/features/institution/pages/StaffListPage") },
                { path: "staff/new", lazy: () => import("@/features/institution/pages/StaffNewPage") },
                { path: "staff/:id", lazy: () => import("@/features/institution/pages/StaffDetailPage") },
                { path: "staff/:id/roles", lazy: () => import("@/features/institution/pages/StaffRolesPage") },
                { path: "roles", lazy: () => import("@/features/institution/pages/RolesPage") },
                { path: "apps", lazy: () => import("@/features/institution/pages/AppsListPage") },
                { path: "apps/new", lazy: () => import("@/features/institution/pages/AppNewPage") },
                { path: "apps/join-keys", lazy: () => import("@/features/institution/pages/AppJoinKeysPage") },
                { path: "apps/:clientId/users", lazy: () => import("@/features/institution/pages/AppUsersPage") },
                { path: "apps/:clientId/join-key", lazy: () => import("@/features/institution/pages/AppJoinKeyPage") },
                { path: "employees", lazy: () => import("@/features/institution/pages/EmployeesPage") },
                { path: "employees/provision", lazy: () => import("@/features/institution/pages/EmployeesProvisionPage") },
                { path: "access/matrix", lazy: () => import("@/features/institution/pages/AccessMatrixPage") },
                { path: "access/matrix/import", lazy: () => import("@/features/institution/pages/AccessMatrixImportPage") },
                { path: "access/revoke", lazy: () => import("@/features/institution/pages/AccessRevokePage") },
                { path: "audit", lazy: () => import("@/features/institution/pages/AuditPage") },
              ],
            },
          ],
        },

        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ];
}

export function createAppRouter(qc: QueryClient) {
  return createBrowserRouter(buildRoutes(qc), {
    future: { v7_relativeSplatPath: true, v7_fetcherPersist: true, v7_normalizeFormMethod: true, v7_partialHydration: true, v7_skipActionErrorRevalidation: true },
  });
}
