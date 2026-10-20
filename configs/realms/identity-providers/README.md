# External identity-provider templates

Templates for Keycloak identity brokering. Each file is a snippet of an
`identityProviders` array entry — paste it into the relevant realm JSON
(under the top-level `identityProviders` key), fill in the placeholders,
and re-import the realm. Both templates ship with `enabled: false` so they
are inert until explicitly activated.

## nida-eid-template.json

OIDC federation with NIDA (Rwanda's National Identification Agency) for the
citizens realm. Once activated, the Keycloak login page surfaces a
"Sign in with NIDA" button.

**Required before enabling:**

1. NIDA-side client registration. NIDA must register the wrapper as an OIDC
   relying party. Required values you'll get back:
   - `client_id`, `client_secret`
   - Authorization, token, and userinfo endpoints
   - JWKS URI and issuer claim
2. Wrapper-side `.env`: set `ENABLE_NIDA_BROKER=True`.
3. Realm config: merge this template into `configs/realms/government-citizens-realm.json`
   under the `identityProviders` array. Replace every `REPLACE_WITH_...`
   placeholder. Flip `enabled` to `true`.
4. Re-import the realm (`make clean && make start && make setup`) or
   apply via the Keycloak admin console.

**Attribute mapping:** NIDA's userinfo response will need mappers so the
national ID number, full name, and date of birth land in the right
Keycloak attributes. Add a `mappers` array on the broker config:

```json
"mappers": [
  {
    "name": "nida-id-mapper",
    "identityProviderAlias": "nida-eid",
    "identityProviderMapper": "oidc-user-attribute-idp-mapper",
    "config": {
      "claim": "national_id",
      "user.attribute": "nationalId",
      "syncMode": "FORCE"
    }
  }
]
```

Mappers should be finalised once NIDA publishes the exact claim names in
their OIDC userinfo response.
