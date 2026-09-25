# Enabling Public Hostnames

CraftPanel can expose Minecraft servers on public hostnames like `survival.mc.example.com` so players can connect without knowing node IPs or ports. This page walks through the one-time setup required before the **expose externally** toggle on a server will work.

Exposure is optional. If you never enable it, servers are still reachable within their Docker network and by node IP + port — sufficient for cross-node proxy setups.

## Prerequisites

- A **Cloudflare account** with a zone already added (your domain's NS records at the registrar must already point at Cloudflare). CraftPanel does not delegate domains.
- At least one **node** whose `public_ip` is set and static. CraftPanel points per-server A records at this IP; if it changes, existing records break until master re-points them (e.g. on migration).
- Master reachable, with the `master` container restarted when you change its env vars.

## Step 1 — Create a Cloudflare API token

CraftPanel needs a token with permission to read the zone and create/update/delete DNS records.

1. Cloudflare dashboard → **My Profile** → **API Tokens** → **Create Custom Token**
2. Under **Permissions**, add:
    - **Zone** → **DNS** → **Edit**
    - **Zone** → **Zone** → **Read**
3. Under **Zone Resources**, restrict to the specific zone you'll use (or `All zones` if you manage several)
4. Create and copy the token. Cloudflare shows it only once.

!!! warning "Client IP Address Filtering"
    If you enable the token's **Client IP Address Filtering**, master's egress IP must be in the
    allowlist — otherwise Cloudflare rejects every DNS call with
    `9109 Cannot use the access token from location: <ip>` (surfaced as a `502`). Either add that IP
    or leave IP filtering off and rely on the zone-scoped permissions above.

## Step 2 — Find your Zone ID

Cloudflare dashboard → select your zone → **Overview** tab → right-hand sidebar shows **Zone ID** (a 32-character hex string like `023e105f4ecef8ad9ca31a8482d7aca9`).

Master validates this format strictly (`^[a-f0-9]{32}$`); copy it exactly.

## Step 3 — Set the DNS provider and token in Settings

Go to **Settings** in the panel UI → **DNS (Cloudflare)** section:

1. Set **DNS Provider** to `Cloudflare`
2. Paste the token from Step 1 into **Cloudflare API Token**
3. Save

The token is stored encrypted in the database and is never shown again — the field only reports whether one is configured. Saving verifies the credentials against the zone before persisting: a rejected token returns `422` instead of silently breaking DNS management.

## Step 4 — Configure the global DNS settings

The zone ID and domain suffix are set **once for the whole install** — there is only ever one Cloudflare API token, so per-network DNS configuration would be redundant.

Go to **Settings** in the panel UI → **DNS (Cloudflare)** section → fill in **DNS Zone ID** and **DNS Domain Suffix** → Save.

`dns_domain_suffix` is the parent domain — per-server subdomains become `<sub>.mc.example.com`. Until both fields are set, no server can be exposed externally: enabling exposure returns `422` with `"No DNS zone configured (set dns_zone_id and dns_domain_suffix in System Settings)"`.

## Step 5 — Static DNS records you must create

CraftPanel creates per-server `<sub>.mc.example.com` A records automatically when exposure is toggled on. You do **not** need to pre-create those.

What you may want to create yourself, depending on your topology:

| Record                | Why                                                                                           |
|-----------------------|-----------------------------------------------------------------------------------------------|
| `*.mc.example.com` A  | Wildcard fallback so unknown subdomains still resolve to a node IP (optional, mc-router-only deployments) |
| `node1.mc.example.com` A | If you reference nodes by hostname anywhere; CraftPanel itself uses IPs, not hostnames.      |

The zone's parent domain (`example.com`) and its NS delegation must already be live at Cloudflare before this step.

## Step 6 — Expose a server

With the provider/token (Step 3) and the global DNS settings saved (Step 4), the exposure toggle works. On a server's detail page:

1. Toggle **expose externally** on
2. Enter a subdomain (e.g. `survival`) — master validates uniqueness
3. Master creates an A record `survival.mc.example.com` → node's `public_ip`, TTL 60 seconds
4. The mc-router label `mc-router.host=survival.mc.example.com` is applied to the container
5. The hostname is shown on the server detail page for players to connect to

Disabling exposure deletes the A record automatically. Deleting the server also deletes the record. Migrating the server to another node re-points the record to the new node's IP.

## Notes & limits

- **TTL is 60 seconds** and is hardcoded as the default in `DnsProvider.createARecord`. There is no panel-side setting to change it today; you can lower Cloudflare's "Minimum TTL" zone override if you need faster propagation, but the API record itself is created at 60s.
- **DNS is global, not per-network** — one zone ID and one domain suffix for the whole install, matching the single global `cf_api_token` setting.
- **Other DNS providers** (Route53, etc.) — the `DnsProvider` interface is ready, but only Cloudflare is implemented.

## Next steps

- [Creating a Server](creating-a-server.md)
