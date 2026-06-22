# Dedicated `craftpanel-rsync` Docker Image

CraftPanel does **not** build and publish a purpose-built `craftpanel-rsync`
image for migration transfers. Migrations run `alpine:latest` and install
`rsync` at runtime via `apk`.

## Why this is out of scope

rsync runs only during a server migration — a rare, manually-triggered,
non-hot-path operation. The runtime `apk add rsync` adds roughly two seconds
to a transfer that already takes minutes for any real world save. That cost is
invisible in practice.

A dedicated image, by contrast, is a permanent tax: a third image to build,
version, and push in CI alongside master/agent/frontend, plus registry
footprint, forever — to save two seconds on an infrequent operation. The
trade is lopsided.

Crucially, the seam already exists. Both migration commands carry an
`rsync_image` field:

```kotlin
// agent/.../handlers/MigrationHandler.kt
val rsyncImage = cmd.rsyncImage.ifEmpty { "alpine:latest" }
```

Anyone who later wants a pinned, pre-baked image just sets `rsync_image` on
`PrepareRsyncReceiveCommand` / `StartRsyncCommand` and provisions it out of
band. No code change needed. So this stays out of scope until there's a
concrete reason to pay the CI cost — e.g. measured migration-startup pain, or
`apk` repo flakiness biting in production.

## Prior requests

- `.scratch/rsync-image/01-decide-rsync-image-strategy.md` — "Decide on dedicated rsync Docker image vs alpine:latest"
