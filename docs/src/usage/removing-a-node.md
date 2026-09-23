# Removing a Node

Decommissioning takes a node out of service. Master marks it `DECOMMISSIONED`, stops accepting its
control stream, and prevents new servers from being placed on it. The node record is kept (for
history) — decommissioning is a soft removal.

## Before you decommission

A node must have **no servers assigned** to it. If any remain, master rejects the request with:

```
409 Node has active servers
```

Move them first with [Migration](../migration/index.md), or delete them. A node can only be
decommissioned while it is `ACTIVE` or `PENDING`; any other status returns
`409 Node cannot be decommissioned`.

## Decommission from the UI

On the **Nodes** screen, open the node and use the decommission action (requires the `system.nodes`
permission). The node's status changes to `DECOMMISSIONED`.

## Decommission via the API

```http
DELETE /api/nodes/{id}
```

See the [Nodes API reference](../api/nodes.md) for request/response details.

## Clean up the node host

Decommissioning changes master's view only — it does not touch the machine. On the node host:

1. Stop and remove the agent container:

   ```bash
   docker compose down agent
   ```

2. Optionally remove the agent's `agent-config` volume, which holds the persisted node key and
   cached CA certificate. Removing it is only necessary if you intend to re-add the host (see
   below).

3. Server data under `HOST_DATA_PATH` is **not** deleted by decommissioning. Delete it manually if
   the host is being retired, after confirming no backups are needed.

## Re-adding the same host later

Because a decommissioned node's key is refused, re-adding means registering afresh:

1. Remove the agent's persisted node key (`NODE_KEY_FILE`, default `/app/config/node.key` in the
   `agent-config` volume).
2. Start the agent with the current `NODE_BOOTSTRAP_TOKEN`.
3. It calls `RegisterNode` and appears as a **new** `PENDING` node — trust it as usual (see
   [Adding a Node](adding-a-node.md)).

The old `DECOMMISSIONED` record remains in the node list; it has no effect on the new registration.

## Related

- [Adding a Node](adding-a-node.md) — registering and trusting nodes
- [Node Management](../nodes/index.md) — registration protocol and agent configuration
- [Migration](../migration/index.md) — moving servers off a node first
