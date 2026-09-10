# First Login & Setup

After [deploying the stack](deployment.md), log in at `https://<DOMAIN>` with the admin credentials you set in `.env`:

- Email: `ADMIN_EMAIL`
- Password: `ADMIN_PASSWORD`

This account is a **Super Admin** — it has every permission node.

## Secure the deployment

The admin seed only runs once, against an empty users table, but the credentials remain in your `.env` file afterward. Remove `ADMIN_EMAIL` and `ADMIN_PASSWORD` from `.env` after the first successful login — the seed won't run again, and leaving real credentials in a plaintext env file is an unnecessary risk.

## Reset the admin password

If the admin password is lost, or you need to force a change, set `ADMIN_RESET_PASSWORD=true` with a new `ADMIN_PASSWORD` in `.env` and restart the stack:

```bash
ADMIN_PASSWORD=<new admin password>
ADMIN_RESET_PASSWORD=true
```

(`ADMIN_RESET_PASSWORD` maps to the `CRAFTPANEL_ADMIN_RESET_PASSWORD` container variable.) On startup master re-hashes the password for the user matching `ADMIN_EMAIL` (it works regardless of how many users exist), revokes their existing sessions, and flags the account so the admin must choose a new password on their next login before they can use the panel.

Remove `ADMIN_RESET_PASSWORD` from `.env` after the restart, then log in with the new password and change it when prompted. See [Configuration & Secrets](../tech-stack/configuration.md#reset-the-admin-password) for details.

## Create additional users

Use the **Users** and **Groups** screens to add team members and assign permissions.

CraftPanel ships four built-in groups:

| Group          | Permissions                                                                                        |
|----------------|-----------------------------------------------------------------------------------------------------|
| Super Admin    | All permissions                                                                                    |
| Server Admin   | All except system settings, users, nodes, resource allocation, and migration                       |
| Operator       | Restart, console, view, backup                                                                      |
| Viewer         | View only                                                                                            |

Permissions can be scoped **globally**, to a **single server**, or to a **network** (all servers in it) — a user can hold different roles on different servers/networks at once. Create custom groups from the **Groups** screen if the built-in four don't fit; assign users to groups from the **Users** screen.

## Next steps

- [Adding a Node](adding-a-node.md)
- [Creating a Server](creating-a-server.md)
