# Build & Packaging

All build, packaging, and publish workflows are expressed as Gradle tasks. There is no Makefile. Docker is used exclusively for packaging pre-built artifacts — no toolchain runs inside Docker.

## Monorepo structure

```
craftpanel/
├── proto/
│   └── craftpanel/agent/v1/
│       └── craftpanel.proto
├── master/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/
├── agent/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/
├── frontend/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── ...
├── docs/
├── build.gradle.kts        ← root — aggregation tasks, docker plugin
└── settings.gradle.kts
```

The `proto/` directory sits at the repo root and is consumed by both `master` and `agent` as a shared source set. Generated Kotlin stubs land in `build/generated/source/proto/` in each module and are
never committed. See [gRPC](../grpc/schema.md) for the Protobuf Gradle plugin configuration.

---

## Build on host, package with Docker

The principle applied consistently across all three components:

> **Gradle builds the artifact. Docker only copies the result into a minimal runtime image.**

Single-stage Dockerfiles contain no build tooling. They `COPY` the output of a prior Gradle task and set an entrypoint. This keeps images small, keeps the build environment consistent with the
developer's IDE, and avoids the complexity of multi-stage builds that re-invoke Gradle inside Docker.

---

## Master

### Build

```bash
./gradlew :master:installDist
```

Produces a self-contained distribution at `master/build/install/master/` — a `bin/` launcher and a `lib/` directory of JARs.

### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk-alpine
WORKDIR /app
COPY master/build/install/master/ .
EXPOSE 8080 50051
ENTRYPOINT ["bin/master"]
```

`installDist` must be run before building the image. The Gradle Docker task (see [Image build & push](#image-build-push)) declares this as a dependency so it runs automatically.

---

## Agent

### Build

```bash
./gradlew :agent:installDist
```

Produces `agent/build/install/agent/` in the same layout as master.

### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk-alpine
WORKDIR /app
COPY agent/build/install/agent/ .
EXPOSE 9091
ENTRYPOINT ["bin/agent"]
```

### Docker GID

The agent process needs access to the Docker socket on the host node (`/var/run/docker.sock`). The GID of the `docker` group varies between host distributions, so `docker-entrypoint.sh` detects it
at container startup — `stat -c %g` on the mounted socket — and adds the `craftpanel` user to that GID before `su-exec` drops privileges. No `group_add:` or build-time GID needed in compose files.

---

## Frontend

### Build

The frontend is built by Gradle via the `org.siouan.frontend-jdk17` plugin, which manages the Node.js and pnpm toolchains without requiring them to be installed globally on the host.

```bash
./gradlew :frontend:assembleFrontend
```

This invokes `pnpm install` followed by `pnpm build` (Next.js). The output is a standalone Next.js server at:

```
frontend/.next/standalone/
frontend/.next/static/
frontend/public/
```

Next.js is configured with `output: 'standalone'` in `next.config.js`, which produces a self-contained Node.js server with all dependencies inlined.

pnpm is configured with `node-linker=hoisted` in `.npmrc` so that dependencies are installed in a flat `node_modules` layout. This ensures Next.js can trace all required packages into the standalone
output; without it, pnpm's content-addressable store causes missing modules at runtime.

### Dockerfile

```dockerfile
FROM node:22-alpine
WORKDIR /app
COPY .next/standalone/ .
COPY .next/static/ .next/static/
COPY public/ public/
EXPOSE 3000
ENV NODE_ENV=production
ENTRYPOINT ["node", "server.js"]
```

Static assets are copied separately because `standalone/` does not include them — Next.js expects them to be served from `.next/static/` relative to the working directory.

---

## Image build & push

Docker image build and push are handled within Gradle using the `com.flowkode.buildx` plugin (v0.1.0), which wraps `docker buildx build`. Each module configures a `buildx { }` extension block and gets
a `buildxBuild` task. The root build defines aggregation tasks:

```bash
./gradlew dockerBuildAll               # builds master, agent, and frontend images, loads into local daemon
./gradlew dockerPushAll -Ppush=true    # builds and pushes all three images to the registry
```

Push is opt-in: the `push` flag on each module's `buildx { }` block is driven by the `-Ppush=true` Gradle property (default `false`, so `dockerBuildAll` only loads images locally).

### Image naming

Image names are driven by project properties — no hardcoded values:

| Property        | Description     | Example            |
|-----------------|-----------------|--------------------|
| `imageRegistry` | Registry prefix | `ghcr.io/your-org` |
| `imageVersion`  | Image tag       | `1.0.0`, `latest`  |

Pass them at invocation time:

```bash
./gradlew dockerPushAll \
  -PimageRegistry=ghcr.io/your-org \
  -PimageVersion=1.0.0 \
  -Ppush=true
```

Resulting image names:

```
ghcr.io/your-org/craftpanel-master:1.0.0
ghcr.io/your-org/craftpanel-agent:1.0.0
ghcr.io/your-org/craftpanel-frontend:1.0.0
```

If `imageRegistry` is omitted the images are built locally without a registry prefix.

### Task dependencies

The `dockerBuild*` tasks declare `installDist` (master, agent) and `assembleFrontend` (frontend) as dependencies, so a plain `./gradlew dockerBuildAll` runs the full build pipeline end to end without
manual sequencing.

---

## Documentation

Docs are built with MkDocs Material via the `ru.vyarus.mkdocs` Gradle plugin (which manages the Python environment internally):

```bash
./gradlew mkdocsBuild    # produces site/ output
./gradlew mkdocsServe   # live-reload dev server
```

The `mkdocs-print-site-plugin` is included for single-page HTML and PDF export.

---

## JDK

The project targets **JDK 25**. Runtime images use `eclipse-temurin:25-jdk-alpine`. Gradle itself is run with JDK 25 on the host. No other JDK version is supported or tested.