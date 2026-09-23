# Using a Different Reverse Proxy

The bundled `docker-compose.yml` ships Traefik, which handles TLS and routing automatically. If you
front CraftPanel with something else — nginx, Caddy, HAProxy, a cloud load balancer — this page
lists what it must do.

## Routing rules

Two upstreams, split by path:

| Path        | Upstream         | Notes                                                                    |
|-------------|------------------|--------------------------------------------------------------------------|
| `/api/*`    | master `:8080`   | REST API **and** all WebSocket endpoints                                 |
| everything  | frontend `:3000` | The Next.js UI                                                           |

Route all of `/api` to master — it covers the REST API plus the WebSocket endpoints
(`/api/ws`, `/api/ws/console/{id}`, `/api/migrations/{id}/events`). Next.js route handlers cannot
proxy WebSocket upgrades, so WS traffic must reach master directly, not through the frontend.

## Requirements

1. **TLS termination.** Serve the UI over HTTPS. Keep `AUTH_SECURE_COOKIES=true` (default) — the
   browser evaluates the cookie's `Secure` flag against the public scheme, so HTTPS at the proxy is
   enough even though master talks plain HTTP internally.
2. **WebSocket upgrade.** Forward `Upgrade`/`Connection` headers for `/api/*`. Without this, the
   console and live dashboard silently fail to connect.
3. **Long timeouts on `/api/*`.** Console sessions are long-lived and can sit idle; raise the proxy
   read/send timeout (nginx default `60s` will drop them).
4. **Large uploads.** Raise the body-size limit for `/api/*` — file uploads and mod uploads pass
   through it.
5. **CORS.** Set master's `PUBLIC_URLS` to the exact browser origin(s), e.g.
   `https://panel.example.com`. This is what CORS validates against; the app does not trust
   forwarded headers for origin checks.
6. **Health probes.** master answers `GET /health` on `:8080`; frontend answers `GET /healthz` on
   `:3000`.

## nginx example

```nginx
server {
    listen 443 ssl;
    server_name panel.example.com;

    ssl_certificate     /etc/nginx/tls/server.crt;
    ssl_certificate_key /etc/nginx/tls/server.key;

    location /api/ {
        proxy_pass http://master:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        client_max_body_size 4g;
    }

    location / {
        proxy_pass http://frontend:3000;
        proxy_http_version 1.1;
        proxy_set_header Host       $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 4g;
    }
}
```

## Caddy example

Caddy handles TLS and WebSocket upgrades automatically:

```caddyfile
panel.example.com {
    handle /api/* {
        reverse_proxy master:8080
    }
    reverse_proxy frontend:3000
}
```

## Split-subdomain deployments

If the UI and API are on different hostnames (e.g. `panel.example.com` and `api.example.com`):

- Set master's `PUBLIC_URLS` to include **both** origins.
- Set `AUTH_COOKIE_DOMAIN` to the shared parent domain (e.g. `.example.com`) so the refresh-token
  cookie is sent cross-subdomain. Only a shared parent domain works — cookies cannot be shared
  across unrelated domains.
- Set the frontend's `PUBLIC_API_URL` to the browser-facing API origin.

See [Deployment → Split-subdomain deploy](deployment.md#split-subdomain-deploy-optional).

## Related

- [Deployment](deployment.md) — the bundled Traefik setup
- [Environment Variables, Ports & Volumes](environment-variables.md) — `PUBLIC_URLS`, `AUTH_COOKIE_DOMAIN`, ports
- [Troubleshooting](troubleshooting.md) — CORS 403 and connectivity issues
