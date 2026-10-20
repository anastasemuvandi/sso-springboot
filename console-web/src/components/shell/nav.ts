/**
 * Sidebar entries. Showing or hiding an entry is presentation only — the
 * wrapper refuses every endpoint behind a page the caller may not use.
 */
import {
  Activity,
  Building2,
  Code2,
  Fingerprint,
  Grid3x3,
  IdCard,
  KeyRound,
  LayoutDashboard,
  ScrollText,
  Settings,
  ShieldCheck,
  SquareStack,
  UserMinus,
  Users,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  to: string;
  /** i18n key */
  label: string;
  icon: LucideIcon;
  /** Match only the exact path (for index-like entries). */
  end?: boolean;
  /** Institution area: delegated capability needed to show the entry (cosmetic). */
  needs?: "view-members" | "manage-members" | "manage-membership" | "map-roles" | "view-clients" | "manage-clients";
}

export interface NavGroup {
  /** i18n key */
  label?: string;
  items: NavItem[];
}

export const PLATFORM_NAV: NavGroup[] = [
  { items: [{ to: "/dashboard", label: "nav.overview", icon: LayoutDashboard, end: true }] },
  {
    label: "nav.accessControl",
    items: [
      { to: "/users", label: "nav.users", icon: Users },
      // Employees are the IPPIS mirror, including people with no account yet.
      { to: "/employees", label: "nav.employees", icon: IdCard },
      // "Institutions": an institution IS a top-level group (ADR-0001). The
      // route keeps the wrapper's `/agency` name.
      { to: "/agencies", label: "nav.institutions", icon: Building2 },
      { to: "/roles", label: "nav.roles", icon: ShieldCheck },
    ],
  },
  { label: "nav.applications", items: [{ to: "/clients", label: "nav.clients", icon: KeyRound }] },
  {
    label: "nav.observability",
    items: [
      { to: "/system-audit", label: "nav.auditLog", icon: Fingerprint },
      { to: "/audit", label: "nav.activity", icon: Activity },
    ],
  },
  {
    label: "nav.settings",
    items: [
      { to: "/settings", label: "nav.realmSettings", icon: Settings },
      { to: "/api-explorer", label: "nav.apiExplorer", icon: Code2 },
    ],
  },
];

/** Institution (delegated admin) console — pages built in src/features/institution. */
export const INSTITUTION_NAV: NavGroup[] = [
  {
    items: [
      { to: "/institution", label: "nav.overview", icon: LayoutDashboard, end: true },
      { to: "/institution/staff", label: "nav.users", icon: Users, needs: "view-members" },
      { to: "/institution/employees", label: "nav.employees", icon: IdCard, needs: "view-members" },
      { to: "/institution/roles", label: "nav.roles", icon: ShieldCheck, needs: "map-roles" },
      { to: "/institution/apps", label: "nav.clients", icon: SquareStack, needs: "view-clients" },
      { to: "/institution/access/matrix", label: "nav.accessMatrix", icon: Grid3x3, needs: "view-clients" },
      { to: "/institution/access/revoke", label: "nav.revokeAccess", icon: UserMinus, needs: "view-clients" },
      { to: "/institution/audit", label: "nav.auditLog", icon: ScrollText, needs: "view-members" },
    ],
  },
];
