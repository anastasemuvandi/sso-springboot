/**
 * The overview response, shared by `GET /dashboard/overview` (realm-wide) and
 * `GET /institution-admin/overview` (one institution). Every section carries
 * `available` / `error`: a section that could not be read says so instead of
 * showing zeros.
 */
import type { Schemas } from "@/api/types";

export type Overview = Schemas["PlatformOverview"];
export type LeaversSection = Schemas["LeaversSection"];
export type PasswordSetupSection = Schemas["PasswordSetupSection"];
export type LoginSection = Schemas["LoginSection"];
export type AccessSection = Schemas["AccessSection"];
