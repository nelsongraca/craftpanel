# DashboardService as the deep service seam for the dashboard WebSocket

The dashboard WebSocket route (`/api/ws`) previously took four parameters and directly called two repositories plus a concrete gRPC implementation type. We introduced `DashboardService` to absorb snapshot construction, permission-gated event filtering, and agent event subscription — making the route pure transport (auth, get snapshot, collect events, send frames).

## Considered Options

- **Thin service** — absorbs only the 3 DB calls for the snapshot, leaves filtering in the route. Rejected because it leaves the route coupled to repositories and the `DashboardEventFilter` construction.
- **Deep service** — owns filtering, permission checking, and event subscription. Chosen because it eliminates all repository access from the route and makes the permission → filter → envelope pipeline testable as a unit.
- **Inject PermissionResolver as a functional interface** — rejected in favor of injecting the existing `PermissionResolver` object through Koin, which is less interface surface.
