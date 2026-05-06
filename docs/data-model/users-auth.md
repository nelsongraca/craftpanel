# Users & Auth

## `users`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `username` | VARCHAR(64) | Unique; used for login |
| `email` | VARCHAR(255) | Unique; reserved for future notification delivery |
| `password_hash` | TEXT | Argon2id hash — plaintext never stored |
| `is_active` | BOOLEAN | `false` blocks login without deleting the record |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

## `refresh_tokens`

Long-lived tokens issued at login. Stored as HttpOnly cookies in the browser; only the hash is persisted.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `user_id` | UUID | FK → `users`, CASCADE DELETE |
| `token_hash` | TEXT | SHA-256 of the raw token value |
| `expires_at` | TIMESTAMPTZ | Absolute expiry |
| `revoked` | BOOLEAN | `true` = explicitly invalidated before expiry |
| `created_at` | TIMESTAMPTZ | |

!!! note
    Refresh tokens are rotated on every use — the old token is revoked and a new one is issued. Session revocation sets `revoked = true` on all tokens for a given user.
