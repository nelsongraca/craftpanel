# Users & Groups

Base path: `/api/v1`

---

## Users

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/users` | `system.users` | List all users |
| POST | `/users` | `system.users` | Create a user |
| GET | `/users/{id}` | `system.users` | Get a user |
| PATCH | `/users/{id}` | `system.users` | Update username, email, or active state |
| DELETE | `/users/{id}` | `system.users` | Delete a user |
| POST | `/users/{id}/password` | `system.users` | Reset another user's password |
| POST | `/users/me/password` | authenticated | Change own password |
| GET | `/users/{id}/sessions` | `system.users` | List active sessions |
| DELETE | `/users/{id}/sessions` | `system.users` | Revoke all sessions for a user |
| GET | `/users/{id}/assignments` | `system.users` | List group assignments |
| POST | `/users/{id}/assignments` | `system.users` | Add a group assignment |
| DELETE | `/users/{id}/assignments/{assignmentId}` | `system.users` | Remove a group assignment |

---

### `GET /users`

**Response `200`:**

```json
{
  "users": [
    {
      "id": "<uuid>",
      "username": "jsmith",
      "email": "j@example.com",
      "is_active": true,
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

---

### `POST /users`

**Request:**

```json
{
  "username": "jsmith",
  "email": "j@example.com",
  "password": "initial-password"
}
```

**Response `201`:**

```json
{
  "id": "<uuid>",
  "username": "jsmith",
  "email": "j@example.com",
  "is_active": true,
  "created_at": "2026-05-04T10:00:00Z"
}
```

**Errors:** `422` if username or email is already taken.

---

### `PATCH /users/{id}`

All fields optional — only provided fields are updated.

**Request:**

```json
{
  "username": "jsmith2",
  "email": "jsmith2@example.com",
  "is_active": false
}
```

**Response `200`:** updated user object.

**Errors:** `422` if the new username or email is already taken.

---

### `POST /users/{id}/password`

Resets another user's password. Does not require the current password.

**Request:**

```json
{
  "password": "new-password"
}
```

**Response `204`.**

---

### `POST /users/me/password`

**Request:**

```json
{
  "current_password": "old-password",
  "new_password": "new-password"
}
```

**Response `204`.**

**Errors:** `401` if `current_password` is incorrect.

---

### `GET /users/{id}/sessions`

**Response `200`:**

```json
{
  "sessions": [
    {
      "id": "<uuid>",
      "created_at": "2026-05-04T09:00:00Z",
      "expires_at": "2026-06-04T09:00:00Z"
    }
  ]
}
```

---

### `POST /users/{id}/assignments`

**Request:**

```json
{
  "group_id": "<uuid>",
  "scope_type": "SERVER",
  "scope_id": "<server-uuid>"
}
```

`scope_id` must be omitted or `null` when `scope_type` is `GLOBAL`.

**Response `201`:**

```json
{
  "id": "<uuid>",
  "group_id": "<uuid>",
  "scope_type": "SERVER",
  "scope_id": "<server-uuid>",
  "created_at": "2026-05-04T10:00:00Z"
}
```

**Errors:** `409` if the assignment already exists.

---

### `DELETE /users/{id}/assignments/{assignmentId}`

**Response `204`.**

---

## Groups

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/groups` | `system.users` | List all groups |
| POST | `/groups` | `system.users` | Create a group |
| GET | `/groups/{id}` | `system.users` | Get group and its permissions |
| PATCH | `/groups/{id}` | `system.users` | Update group name |
| DELETE | `/groups/{id}` | `system.users` | Delete group (non-system groups only) |
| PUT | `/groups/{id}/permissions` | `system.users` | Replace full permission set |

---

### `POST /groups`

**Request:**

```json
{
  "name": "Moderators"
}
```

**Response `201`:**

```json
{
  "id": "<uuid>",
  "name": "Moderators",
  "is_system": false,
  "permissions": [],
  "created_at": "2026-05-04T10:00:00Z"
}
```

**Errors:** `422` if name is already taken.

---

### `PATCH /groups/{id}`

**Request:**

```json
{
  "name": "Senior Moderators"
}
```

**Response `200`:** updated group object.

**Errors:** `409` if the group is a system group.

---

### `DELETE /groups/{id}`

**Response `204`.**

**Errors:** `409` if the group is a system group.

---

### `PUT /groups/{id}/permissions`

Full replacement — permissions not included in the request are removed.

**Request:**

```json
{
  "permissions": [
    "server.view",
    "server.console",
    "server.restart"
  ]
}
```

**Response `200`:** updated group object with full permissions array.

**Errors:** `400` if any permission value is not a valid `permission_node`. `409` if the group is a system group.
