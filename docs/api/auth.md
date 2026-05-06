# Auth

Base path: `/api/v1/auth`

| Method | Path | Permission | Description |
|---|---|---|---|
| POST | `/auth/login` | — | Authenticate with username and password |
| POST | `/auth/refresh` | — | Rotate refresh token, get new access token |
| POST | `/auth/logout` | authenticated | Revoke current session |
| POST | `/auth/logout-all` | authenticated | Revoke all sessions for the current user |

---

## `POST /auth/login`

**Request:**

```json
{
  "username": "admin",
  "password": "secret"
}
```

**Response `200`:**

```json
{
  "access_token": "<jwt>",
  "expires_in": 900
}
```

Sets a `refresh_token` cookie (`HttpOnly; Secure; SameSite=Strict`). `expires_in` is seconds until the access token expires.

**Errors:** `401` if credentials are invalid or the user is inactive.

---

## `POST /auth/refresh`

No request body. Reads the refresh token from the cookie.

**Response `200`:**

```json
{
  "access_token": "<jwt>",
  "expires_in": 900
}
```

Issues a new refresh token cookie and revokes the old one (rotation on every use).

**Errors:** `401` if the cookie is missing, expired, or revoked.

---

## `POST /auth/logout`

No request body. Revokes the current session's refresh token.

**Response `204`.**

---

## `POST /auth/logout-all`

No request body. Revokes all refresh tokens for the authenticated user, ending all active sessions.

**Response `204`.**
