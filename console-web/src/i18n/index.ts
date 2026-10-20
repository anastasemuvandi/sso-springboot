/**
 * i18n: English + Kinyarwanda. One namespace ("translation") with nested keys,
 * grouped by feature: `common.*`, `nav.*`, `shell.*`, `dashboard.*`,
 * `clients.*`, ... — see README "i18n".
 *
 * `src/i18n/parts/<ns>.<lang>.json` files (if any) are merged in under their
 * namespace; `scripts/i18n-fold.mjs` folds them into en.json / rw.json.
 */
import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import en from "./en.json";
import rw from "./rw.json";

export const LANGUAGES = ["en", "rw"] as const;
export type Language = (typeof LANGUAGES)[number];
export const LANGUAGE_STORAGE_KEY = "gorsso.console.lang";

type Tree = Record<string, unknown>;

const parts = import.meta.glob<{ default: Tree }>("./parts/*.json", { eager: true });

function isTree(v: unknown): v is Tree {
  return Boolean(v) && typeof v === "object" && !Array.isArray(v);
}

function deepMerge(a: Tree, b: Tree): Tree {
  const out: Tree = { ...a };
  for (const [k, v] of Object.entries(b)) {
    const cur = out[k];
    out[k] = isTree(cur) && isTree(v) ? deepMerge(cur, v) : v;
  }
  return out;
}

function withParts(base: Tree, lang: Language): Tree {
  let out: Tree = base;
  for (const [path, mod] of Object.entries(parts)) {
    const m = /\/([\w-]+)\.(en|rw)\.json$/.exec(path);
    if (m && m[2] === lang && m[1]) out = deepMerge(out, { [m[1]]: mod.default });
  }
  return out;
}

export const resources = {
  en: { translation: withParts(en as Tree, "en") },
  rw: { translation: withParts(rw as Tree, "rw") },
};

function initialLanguage(): Language {
  try {
    const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
    if (stored === "en" || stored === "rw") return stored;
  } catch {
    /* storage unavailable */
  }
  return "en";
}

void i18n.use(initReactI18next).init({
  resources,
  lng: typeof window === "undefined" ? "en" : initialLanguage(),
  fallbackLng: "en",
  interpolation: { escapeValue: false },
  returnNull: false,
});

export function setLanguage(lang: Language) {
  void i18n.changeLanguage(lang);
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, lang);
  } catch {
    /* ignore */
  }
  document.documentElement.lang = lang;
}

export default i18n;
