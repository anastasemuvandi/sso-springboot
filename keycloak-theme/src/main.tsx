import { createRoot } from "react-dom/client";
import { StrictMode } from "react";
import { KcPage } from "./kc.gen";

// Keycloak injects window.kcContext from the FTL that Keycloakify generates.
createRoot(document.getElementById("root")!).render(
    <StrictMode>
        {!window.kcContext ? <h1>No Keycloak Context</h1> : <KcPage kcContext={window.kcContext} />}
    </StrictMode>
);
