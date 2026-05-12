# Security

## Communication Boundaries

| Channel | Protocol | Authentication |
|---|---|---|
| Browser → Master | HTTPS + WSS (TLS) | JWT bearer token |
| Master ↔ Agent | gRPC over mTLS | Node key (256-bit, stored hashed in DB) |
| Agent → Docker | Unix socket (local only) | Not exposed over network |
| Players → mc-router | Plain Minecraft protocol TCP (port 25565) | n/a |
| mc-router → Container | Docker bridge or host port | n/a |

## Node Bootstrap and Keys

Node registration is agent-initiated. Agents authenticate in two phases:

**Bootstrap (first registration only):**
A shared `node_bootstrap_token` is configured in the master config file. The agent presents this token once when calling `RegisterNode` over gRPC. Master validates the token, creates a `PENDING` node record, generates a unique 256-bit node key, and returns it to the agent. The agent persists the key to its local config file on the host.

**Ongoing authentication:**
All subsequent connections use the node's unique key, presented via `IdentifyNode`. The bootstrap token is not used again after first registration.

- Node keys are stored as SHA-256 hashes in master's database — the raw key is never stored
- Keys can be rotated from the master UI at any time; the old key is immediately invalidated
- Key revocation prevents the agent from connecting until re-registration
- A leaked bootstrap token can only produce `PENDING` node records — harmless until an admin explicitly trusts them. Rotate in master config and restart if compromised.
- Nodes remain `PENDING` after registration and cannot be used until an admin trusts them from the UI

## TLS

All network communication between components uses TLS.

- **Browser ↔ Master** — standard HTTPS certificate (Let's Encrypt or operator-provided)
- **Master ↔ Agent** — TLS on the gRPC channel. For nodes on a private network, self-signed certificates with the CA bundled into the agent config are acceptable. For nodes on the public internet, a wildcard certificate or per-node certificate is recommended.

## Docker Socket Access

The Docker socket (`/var/run/docker.sock`) is accessed only by the node agent process, on the local machine. It is never exposed over the network. The agent communicates with master via gRPC; master never has direct access to any Docker socket.

## File Explorer Sandboxing

The file explorer is rooted at the server's data directory. Path traversal outside the server's own directory is rejected by the agent — the restriction is enforced server-side, not only in the UI.

## Credential Storage

- User passwords: Argon2id hashed, never stored in plaintext
- Node keys: stored as SHA-256 hashes in PostgreSQL — raw keys are never persisted
- DNS provider API keys: deployment-time config only — stored in config file or mounted secrets, never in the database. See [Configuration & Secrets](configuration.md).
