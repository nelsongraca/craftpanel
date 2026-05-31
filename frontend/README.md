# CraftPanel Frontend

Next.js 16 frontend for CraftPanel — a self-hosted multi-user multi-node Minecraft server management platform.

## Stack

- **Framework:** Next.js 16 (App Router, standalone output)
- **Styling:** Tailwind CSS v4 + shadcn/ui (base-nova style, `@base-ui/react`)
- **Fonts:** Barlow (body), Barlow Condensed (headings), JetBrains Mono (data values)
- **Package manager:** pnpm

## Dev

```bash
pnpm install
pnpm dev
```

Open [http://localhost:3000](http://localhost:3000).

## Build

Built via Gradle from the repo root — do not run `pnpm build` directly in CI:

```bash
# from repo root
./gradlew :frontend:assembleFrontend   # pnpm build
./gradlew :frontend:dockerBuildImage   # packages into Docker image
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MASTER_URL` | `http://localhost:8080` | Base URL of the master backend. Next.js proxies all `/api/*` requests to `$MASTER_URL/api/*` via rewrites. Set to the internal service address in Docker (e.g. `http://master:8080`). |

## Conventions

- Dark-only — no light theme; `dark` class is hardcoded on `<html>`
- Never use raw hex colours — always use token classes (`bg-surface`, `text-accent`, etc.)
- Custom tokens are defined in `app/globals.css` via `@theme inline`
- Add shadcn components with: `pnpm dlx shadcn add <component>`
