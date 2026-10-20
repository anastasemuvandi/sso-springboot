import { createContext } from "react";
import type { AuthClient } from "./client";

export const GorssoContext = createContext<AuthClient | null>(null);
