# Runbook: TLS

| | |
|---|---|
| Applies to | staging and production |
| Pattern | TLS terminates at a reverse proxy (nginx or https-portal in `deploy/docker-compose.prod.yml`); services speak HTTP on the compose network |
| Related | `production-sizing.md` (DNS, ports), `onboard-institution.md` (redirect URIs) |

## 1. Choose the certificate source

| Option | Needs |
|---|---|
| A. GoR PKI / internal CA (recommended for an internal deployment) | Certificate and key for the three hostnames (or a wildcard), mounted into an nginx container; renewal is scripted |
| B. Let's Encrypt DNS-01 | An API-capable DNS provider; works with no public inbound |
| C. Let's Encrypt HTTP-01 (https-portal default) | Public DNS and public inbound port 80 |

Minimum TLS 1.2. HSTS on all three hosts once TLS is confirmed working.

Whichever CA you use, distribute its chain to every relying party. Java clients (including Spring apps using the starter) fail to fetch JWKS from a Keycloak signed by an internal CA until it is in their truststore. Add this to the integration checklist.

## 2. Hostnames

```
https://sso.gov.rw            -> wrapper:8000
https://admin.sso.gov.rw      -> console-bff:3001
https://keycloak.sso.gov.rw   -> keycloak:8080
```

Set them in `.env` (`PROD_DOMAIN_SSO`, `PROD_DOMAIN_ADMIN`, `PROD_DOMAIN_KEYCLOAK`). Decide them once: Keycloak writes its hostname into every token `iss`.

## 3. Proxy requirements

- Send `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` (and `X-Forwarded-Port` if not 443).
- Enlarge buffers; Keycloak's headers exceed nginx defaults:

  ```nginx
  proxy_buffer_size 128k;
  proxy_buffers 4 256k;
  proxy_busy_buffers_size 256k;
  large_client_header_buffers 4 32k;
  ```

- Redirect 80 to 443.
- Do not expose Keycloak's `/admin` on the public hostname unless required; restrict it to the admin VLAN.
- Health probes go to `keycloak:9000/health/ready` over the internal network, never through the public host.

## 4. Service settings

### Keycloak

```bash
KC_HOSTNAME=https://keycloak.sso.gov.rw     # full URL, scheme included (hostname v2)
KC_HOSTNAME_STRICT=false
KC_HTTP_ENABLED=true                         # HTTP inside the network, behind the proxy
KC_PROXY_HEADERS=xforwarded                  # KC_PROXY no longer exists
KC_PROXY_TRUSTED_ADDRESSES=172.16.0.0/12     # only the proxy's network may set X-Forwarded-*
```

Set `KC_PROXY_HEADERS` only when a proxy is in front. Without one, trusting the headers lets any caller choose the address Keycloak stamps into `iss`.

Realm: set `sslRequired` to `external` on all three realms after the cutover (realm settings, or `realm_ssl_required = "external"` in the Terraform tfvars for `government-internal`).

### Wrapper

```bash
API_BASE_URL=https://sso.gov.rw
KEYCLOAK_SERVER_URL=http://keycloak:8080            # server side, unchanged
KEYCLOAK_PUBLIC_URL=https://keycloak.sso.gov.rw     # browser redirects
KEYCLOAK_LOCAL_ISSUER=https://keycloak.sso.gov.rw   # must equal KC_HOSTNAME; the issuer is pinned to it
ALLOWED_ORIGINS=https://admin.sso.gov.rw,https://sso.gov.rw
FORWARD_HEADERS_STRATEGY=framework                  # honour X-Forwarded-* from the proxy
```

### Console BFF

```bash
SSO_BASE_URL=http://wrapper:8000
SSO_PUBLIC_URL=https://sso.gov.rw
ADMIN_UI_REDIRECT_URI=https://admin.sso.gov.rw/login/oauth2/code/sso-admin-ui
ADMIN_UI_POST_LOGOUT_REDIRECT_URI=https://admin.sso.gov.rw/
COOKIE_SECURE=true
BFF_FORWARD_HEADERS_STRATEGY=framework
```

`COOKIE_SECURE=false` is only for plain-HTTP dev; with TLS it must be `true`, and with plain HTTP it must be `false` or the browser drops the cookie and login loops.

### Keycloak clients

Register the https URIs on `sso-admin-ui` (redirect `https://admin.sso.gov.rw/login/oauth2/code/sso-admin-ui`, post-logout `https://admin.sso.gov.rw/`) and on every institution client (`extra_redirect_uris` in the environment's tfvars). Keep the HTTP URIs until the cutover is done, then remove them.

### Theme link

```bash
GORSSO_ACCOUNT_SETUP_URL=https://sso.gov.rw/account/setup-link
```

## 5. Cutover order

1. Issue certificates and start the proxy alongside the HTTP setup.
2. Add the https redirect URIs to the clients.
3. Switch Keycloak (`KC_HOSTNAME`, `KC_PROXY_HEADERS`, `KC_PROXY_TRUSTED_ADDRESSES`) and restart it. Every token issued from now on carries the https issuer.
4. Switch the wrapper (`KEYCLOAK_PUBLIC_URL`, `KEYCLOAK_LOCAL_ISSUER`, `API_BASE_URL`, `ALLOWED_ORIGINS`) and the BFF (`SSO_PUBLIC_URL`, redirect URIs, `COOKIE_SECURE=true`) in the same window. Tokens with the old issuer are rejected (401), so users sign in again.
5. Tell integrating apps to switch their issuer and endpoints; distribute the CA chain.
6. Set realm `sslRequired=external`, remove HTTP redirect URIs, close the old ports.

## 6. Verify

```bash
curl -sI https://keycloak.sso.gov.rw/realms/government-internal/.well-known/openid-configuration | head -1
curl -s https://keycloak.sso.gov.rw/realms/government-internal/.well-known/openid-configuration | jq -r .issuer
#   -> https://keycloak.sso.gov.rw/realms/government-internal
curl -s https://sso.gov.rw/oauth2/.well-known/openid-configuration | jq '.authorization_endpoint'
openssl s_client -connect sso.gov.rw:443 -servername sso.gov.rw </dev/null 2>/dev/null | openssl x509 -noout -dates -issuer
```

Then sign in to the console and confirm the session cookie is `Secure; HttpOnly; SameSite=Lax`.

## 7. Troubleshooting

| Symptom | Cause |
|---|---|
| Every token rejected by the wrapper (401) | `KEYCLOAK_LOCAL_ISSUER` differs from `KC_HOSTNAME` |
| Redirects go to `http://keycloak:8080` | Proxy not sending `X-Forwarded-*`, or `KC_PROXY_HEADERS` unset |
| `iss` changes with the client's Host header | `KC_HOSTNAME` unset |
| Console login loops | `COOKIE_SECURE` does not match the scheme, or the BFF is not honouring `X-Forwarded-Proto` |
| `Invalid parameter: redirect_uri` | https URI not registered on the client |
| 502 from the proxy on login | Proxy header buffers too small |
| Java apps fail on JWKS with PKIX errors | Internal CA not in their truststore |
| Renewal failed silently | Alert on certificate expiry under 21 days |
