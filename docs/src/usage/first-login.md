# First Login & Setup

After [deploying the stack](deployment.md), log in at `https://<DOMAIN>` with the admin credentials you set:

- Email: `CRAFTPANEL_ADMIN_EMAIL`
- Password: `CRAFTPANEL_ADMIN_PASSWORD`

This account is a **Super Admin** — it has every permission node.

## Secure the deployment

The admin seed only runs once, against an empty users table, but the credentials remain in your compose environment afterward. Remove `CRAFTPANEL_ADMIN_EMAIL` and `CRAFTPANEL_ADMIN_PASSWORD` from your `.env` file after the first successful login — see [Configuration & Secrets](../tech-stack/configuration.md#initial-admin-user).

## Create additional users

Use the **Users** and **Groups** screens to add team members and assign permissions. CraftPanel ships four default groups (Super Admin, Server Admin, Operator, Viewer); create custom groups for finer-grained access. See [Access Control](../access-control/index.md) for the full permission model and [Users](../screens/users.md) / [Groups](../screens/groups.md) for the UI.

## Next steps

- [Adding a Node](adding-a-node.md)
- [Creating a Server](creating-a-server.md)
