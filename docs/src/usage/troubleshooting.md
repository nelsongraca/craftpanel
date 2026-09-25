# Troubleshooting

Common startup and connectivity problems, with the exact error and the fix.

## Master refuses to start

Outside `CRAFTPANEL_PROFILE=dev`, master validates its configuration and refuses to boot on unsafe
values. The failure message names the variable:

| Error message                                                                   | Fix                                                                                             |
|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `JWT_SECRET must be set to a non-default value of at least 32 characters`        | Set `JWT_SECRET` to a random string of 32+ characters.                                           |
| `NODE_BOOTSTRAP_TOKEN must be set to a non-default value of at least 16 characters` | Set `NODE_BOOTSTRAP_TOKEN` to a random string of 16+ characters, and use the same value on every agent. |
| `FORWARDING_KEY must be set to a non-default value`                              | Generate one with `openssl rand -base64 32` and set `FORWARDING_KEY`.                            |
| `FORWARDING_KEY must be set to Base64 of 32 raw bytes (AES-256 key)`             | The value is not valid Base64 of exactly 32 bytes — regenerate it.                               |
| `PUBLIC_URLS must be set outside app.profile=dev, or the API will reject every browser request with CORS 403` | Set `PUBLIC_URLS` to the full browser-facing origin(s), e.g. `https://panel.example.com`. |
| `PUBLIC_URLS entry '<url>' must be a full URL, e.g. https://example.com`         | Each entry needs a scheme and host — no trailing path, no bare hostname.                        |
| `<NAME>_FILE points to '<path>' which is not a readable file`                    | A `_FILE` secret path is set but unreadable. Fix the mount/permissions or unset the `_FILE` variable. |

To bypass validation while developing locally only, set `CRAFTPANEL_PROFILE=dev`. Never do this in
production — it also relaxes CORS and disables the HSTS header.

## The browser gets `403` on every API request

CORS is rejecting the origin. `PUBLIC_URLS` must contain the exact origin the browser loads the UI
from — scheme **and** host, e.g. `https://panel.example.com`. If frontend and master are on
different subdomains, list both. The origin must match the `Origin` header exactly (no path, no
trailing slash).

## Cloudflare / DNS exposure does not work

| Error message                                                        | Fix                                                                                     |
|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `422 cf_api_token is required when dns_provider=cloudflare`          | Set **DNS Provider** to `cloudflare` **and** paste a token on the **Settings** page — or switch the provider back to `none`. |
| `422 No DNS zone configured (set dns_zone_id and dns_domain_suffix in System Settings)` | Fill in **DNS Zone ID** and **DNS Domain Suffix** on the **Settings** page.      |
| `502 DNS error: Authentication error` (Cloudflare code `10000`)      | The token can't read/write DNS in that zone: give it **Zone → DNS → Edit** and **Zone → Zone → Read**, scoped to the zone, and check the Zone ID matches. |
| `502 DNS error: Cannot use the access token from location: <ip>` (Cloudflare code `9109`) | The token has **Client IP Address Filtering** that excludes master's egress IP. Add `<ip>` to the token's allowed IPs, or remove the filter. |
| `422 A DNS record for <host> already exists and was not created by CraftPanel; refusing to overwrite it` | A record already exists at that name that this panel did not create (e.g. a CNAME to a tunnel). Rename/remove it, or pick a different subdomain. CraftPanel never overwrites records it doesn't own. |

`dns_provider`, `cf_api_token`, `dns_zone_id` and `dns_domain_suffix` are all **System Settings** —
set them on the **Settings** page (or via `PATCH /api/system/settings`), **not** via environment
variables. Saving runs a quick preflight against the provider and returns `422` if the configured
token can't read the zone, so a bad token/zone is caught at save time instead of when a server is
exposed. The API never returns the token itself — only a `cf_api_token_set` flag; re-entering a
value in the token field replaces it, leaving it blank keeps the stored one.

## Admin account problems

### The initial admin was never created

The seed only runs against an **empty users table**. If any user already exists (for example after
a restore), `CRAFTPANEL_ADMIN_*` is ignored and master starts normally. Log in with an existing
admin instead.

### Forgot the admin password

Set `CRAFTPANEL_ADMIN_RESET_PASSWORD=true` together with a new `CRAFTPANEL_ADMIN_PASSWORD`, restart
master, then remove the reset flag. See
[Reset the admin password](../tech-stack/configuration.md#reset-the-admin-password).

### Retiring the admin seed credentials

The docs recommend removing `ADMIN_EMAIL`/`ADMIN_PASSWORD` after first login, but the bundled
`docker-compose.yml` marks them as required (`:?required`), so deleting or blanking them makes
`docker compose up` fail **before master starts**. Choose one:

- Leave the values in `.env` — the seed will not run again once a user exists, or
- Edit `docker-compose.yml` to drop the `:?required` guard on `CRAFTPANEL_ADMIN_EMAIL` /
  `CRAFTPANEL_ADMIN_PASSWORD` (and the `ADMIN_*` lines from `.env`).

## Node problems

### Node stays `PENDING`

A newly registered node is not usable until an admin trusts it. On the **Nodes** screen, click
**Trust** (requires `system.nodes`). Until then master refuses the node's control stream.

### Node is `REJECTED` or `DECOMMISSIONED`

The node key was rotated or the node was decommissioned. On the node host, delete the persisted key
file (`NODE_KEY_FILE`, default `/app/config/node.key` in the `agent-config` volume) and restart the
agent so it re-registers with the bootstrap token. A decommissioned node must be removed and
re-added.

### Agent fails to connect with a TLS error

Master's certificate does not cover the hostname/IP the agent dials. Add it to master's
`GRPC_TLS_SANS` (auto-generated certs) or reissue the cert with that SAN (BYOC), then restart
master and redeploy the agent. See [Custom gRPC TLS](custom-tls.md).

### Agent exits with `gRPC CA cert required outside dev profile`

The agent has no CA to verify master with. Either let it fetch master's CA into `GRPC_CA_CERT_FILE`
and mount that path writable, or set `GRPC_TLS_CERT` to master's CA PEM. See
[Custom gRPC TLS](custom-tls.md).

## Health probes

| Service  | Probe                          | Notes                                                                                     |
|----------|--------------------------------|-------------------------------------------------------------------------------------------|
| master   | `GET /health` on port `8080`   | Returns `{"status":"ok","version":...}`. Used by the image healthcheck.                    |
| frontend | `GET /healthz` on port `3000`  | Returns frontend and master versions plus `versionMismatch`.                               |
| agent    | heartbeat file                 | The agent touches `HEARTBEAT_FILE` (default `/tmp/agent-heartbeat`) on auth and each metrics tick. |

If `/healthz` reports `versionMismatch: true`, the frontend and master images are different
versions — redeploy both with the same `IMAGE_VERSION`. See [Upgrading](upgrading.md).

## Related

- [Environment Variables, Ports & Volumes](environment-variables.md) — full reference
- [Upgrading](upgrading.md) — version mismatch and rollback
- [Custom gRPC TLS](custom-tls.md) — certificate issues
- [Node Management](../nodes/index.md) — registration protocol
