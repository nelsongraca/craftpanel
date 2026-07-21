# Forwarding secret is master-owned and encrypted-at-rest, pushed to proxy and backends

## Status

accepted (supersedes the "forwarding.secret untouched" clause of ADR-0002)

## Context

ADR-0002 (#36) decided the Velocity `forwarding.secret` is **image-generated and
left untouched** — master never sees it. That holds for the proxy side alone.

#44 requires the *same* secret to be written into every **backend** game server
(`paper-global.yml proxies.velocity.secret`) so modern forwarding is accepted.
Master cannot fan out a secret it does not own. itzg exposes no environment
variable to inject the velocity secret on either the proxy or the backend side
(verified against the image docs), so env-injection is not available. The image
auto-generates the secret only for the proxy, in a file master doesn't read.

## Decision

**Master mints the forwarding secret** (32 random chars) and owns it end-to-end.
It writes the secret to the proxy (`forwarding.secret`) and to each eligible
backend (`paper-global.yml`), both via the #36 patch/`writeFile` path. This
reverses ADR-0002's "untouched" clause: the image no longer owns the secret.

**Storage:** the secret is stored **encrypted at rest** in the DB. The encryption
key lives **outside the DB** — a config file, mounted secret, or env var. It must
never be a DB column; a key stored beside its ciphertext is theater.

CLAUDE.md says "no deployment secrets in the database." The user explicitly
accepted encrypted-in-DB with a master-held key as the exception, because the
alternatives (re-reading an image-generated secret back off the proxy container
after first start) impose a hard ordering trap — the proxy must be started before
any backend can be configured, which races migration and recreate flows.

## Consequences

- **Threat model, stated honestly:** encryption protects against DB-at-rest
  compromise and leaked backups. It does **not** protect against master
  compromise — an attacker who owns master has both the key and the ciphertext.
  This is accepted: master already holds every node key and the JWT signing
  secret; the forwarding secret is not a higher tier than what master already
  guards.
- **Offline backends must stay internal.** Modern/legacy forwarding requires
  `online-mode=false` on each backend (`ONLINE_MODE=false`). An offline backend
  that is directly reachable lets anyone join as any username. Forwarding
  backends must be reachable only via the proxy (they already are — same-node
  internal docker DNS, not exposed). Never expose a forwarding backend directly.
- Enabling forwarding on a backend sets two create-time env vars
  (`ONLINE_MODE=false`, `PATCH_DEFINITIONS`) plus a patch file → `needs_recreate`
  on the backend. Not force-restarted; applies on next start (matches #36).
- The proxy's `forwarding.secret` is now master-written (patch), not
  image-generated. ADR-0002's other decisions (patch mechanism, master renders,
  agent writes, same-node) stand unchanged.
- Secret **rotation** is out of scope for #44 v1 — mint-once only. Rotation would
  re-push to proxy + all backends on the same write path when added later.

## Rejected alternatives

- **Read-back** (image generates, master reads `forwarding.secret` off the proxy
  container after first start) — imposes proxy-before-backend start ordering, a
  state-machine trap that races migration/recreate. Rejected.
- **Env-injection of the secret** — itzg has no env var for the velocity secret
  on proxy or backend. Not available.
- **Key in the DB** — defeats the purpose; encryption becomes theater. Rejected.
