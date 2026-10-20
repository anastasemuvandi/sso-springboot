import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { GorssoProvider } from "@gorsso/react";
import { App } from "./App";
import { gorssoConfig } from "./config";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <GorssoProvider config={gorssoConfig}>
        <App />
      </GorssoProvider>
    </BrowserRouter>
  </StrictMode>,
);
