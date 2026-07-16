# Adding a Node

A node is any machine running the CraftPanel agent alongside Docker. The [deployment](deployment.md) compose file already runs one agent co-located with master — use this guide to attach additional nodes on other hosts.

## Deploy the agent

On the new host, run the agent container pointed at your existing master:

```yaml
services:
  agent:
    image: ghcr.io/nelsongraca/craftpanel/agent:${IMAGE_VERSION:-latest}
    environment:
      MASTER_HOST: panel.example.com
      MASTER_GRPC_PORT: 50051
      NODE_BOOTSTRAP_TOKEN: <same token master was configured with>
      DATA_PATH: /data
      HOST_DATA_PATH: /opt/craftpanel/data
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - agent-config:/app/config
      - /opt/craftpanel/data:/data
    group_add:
      - "999"  # match the host's docker group GID: stat -c %g /var/run/docker.sock
    restart: unless-stopped

volumes:
  agent-config:
```

Master must be reachable on `50051` from this host — this is the port opened in the root `docker-compose.yml`'s `master` service. If master's gRPC TLS uses an auto-generated CA, the agent needs that CA cert; the simplest path is a shared volume (as in the co-located agent) or copying `grpc-ca.crt` to the new host and setting `GRPC_CA_CERT_FILE`.

## Trust the node

On first startup the agent registers itself with master using the bootstrap token and appears in the UI's **Nodes** screen with status `PENDING`. An admin (any user with `system.nodes`) must click **Trust** before the node becomes `ACTIVE` and available for server placement.

## Next steps

- [Creating a Server](creating-a-server.md)
- Full registration protocol, agent env var reference, and data path rules: [Node Management](../nodes/index.md)
