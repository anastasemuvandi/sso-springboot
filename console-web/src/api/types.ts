/**
 * Generated-schema shortcuts.
 *
 * The wrapper (Spring, Jackson `default-property-inclusion: always`) writes
 * EVERY record component of a response, but springdoc does not mark any
 * property `required`, so the generated response types are all-optional.
 * `Present<T>` restores what is on the wire: every key present. springdoc
 * also does not mark nullability, so a value the wrapper leaves empty arrives
 * as null although the type says otherwise; code that renders optional
 * values guards them (`?? "—"`, `formatCount`), as it did against the reference. Request bodies keep the generated optionality — a PATCH
 * sends only what changes.
 */
import type { components, paths } from "./schema";

export type Present<T> = T extends (infer U)[]
  ? Present<U>[]
  : T extends Record<string, unknown>
    ? { [K in keyof T]-?: Present<Exclude<T[K], undefined>> }
    : T;

/** Response-side schema types (all keys present). */
export type Schemas = { [K in keyof components["schemas"]]: Present<components["schemas"][K]> };
export type { paths };

/** Request-side schema types (generated optionality; omit what you do not send). */
export type RequestSchemas = components["schemas"];
