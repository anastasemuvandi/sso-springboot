import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";

import "./index.css";
import i18n from "./i18n";
import { Providers } from "./app/Providers";
import { createQueryClient } from "./app/queryClient";
import { createAppRouter } from "./app/router";

const queryClient = createQueryClient();
const router = createAppRouter(queryClient);
document.documentElement.lang = i18n.language;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Providers client={queryClient}>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </Providers>
  </StrictMode>,
);
