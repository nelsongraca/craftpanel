# Files

Base path: `/api/servers/{id}/files`

All file paths are relative to the server's data directory and passed as a `path` query parameter unless noted otherwise.

| Method | Path                           | Permission     | Description                |
|--------|--------------------------------|----------------|----------------------------|
| GET    | `/servers/{id}/files`          | `server.files` | List directory contents    |
| GET    | `/servers/{id}/files/content`  | `server.files` | Read a file                |
| PUT    | `/servers/{id}/files/content`  | `server.files` | Write a file               |
| POST   | `/servers/{id}/files/upload`   | `server.files` | Upload a file (multipart)  |
| POST   | `/servers/{id}/files/upload-batch` | `server.files` | Upload many files at once |
| GET    | `/servers/{id}/files/download` | `server.files` | Download a file            |
| DELETE | `/servers/{id}/files`          | `server.files` | Delete a file or directory |
| POST   | `/servers/{id}/files/move`     | `server.files` | Move or rename a file      |
| POST   | `/servers/{id}/files/copy`     | `server.files` | Copy a file or directory   |
| POST   | `/servers/{id}/files/mkdir`    | `server.files` | Create a directory         |

---

## `GET /servers/{id}/files`

**Query parameters:**

| Param  | Required | Description                                   |
|--------|----------|-----------------------------------------------|
| `path` | No       | Directory path; defaults to `/` (server root) |

**Response `200`:**

```json
{
  "path": "/plugins",
  "entries": [
    {
      "name": "lithium.jar",
      "is_directory": false,
      "size_bytes": 1048576,
      "modified_at": "2026-05-04T10:00:00Z",
      "permissions": "rw-r--r--"
    },
    {
      "name": "Lithium",
      "is_directory": true,
      "size_bytes": 0,
      "modified_at": "2026-05-04T10:00:00Z",
      "permissions": "rwxr-xr-x"
    }
  ]
}
```

---

## `GET /servers/{id}/files/content`

**Query parameters:**

| Param  | Required | Description |
|--------|----------|-------------|
| `path` | Yes      | File path   |

**Response `200`:**

```json
{
  "path": "/server.properties",
  "content": "view-distance=10\nmax-players=50\n...",
  "encoding": "utf-8"
}
```

`encoding` is `utf-8` for text files or `binary` for non-text files. Binary files should be transferred via the download endpoint instead.

**Errors:** `409` if the path is a directory.

---

## `PUT /servers/{id}/files/content`

**Query parameters:**

| Param  | Required | Description                              |
|--------|----------|------------------------------------------|
| `path` | Yes      | File path — created if it does not exist |

**Request body:** raw file content (`Content-Type: text/plain` or `application/octet-stream`).

**Response `204`.**

---

## `POST /servers/{id}/files/upload`

Multipart file upload. Streams the file through to the agent; it is never buffered whole in master memory.

**Request:** `Content-Type: multipart/form-data`

| Field  | Description                         |
|--------|-------------------------------------|
| `path` | Destination path including filename |
| `file` | File binary                         |

**Response `201`:**

```json
{
  "path": "/plugins/myplugin.jar",
  "size_bytes": 2097152
}
```

---

## `POST /servers/{id}/files/upload-batch`

Uploads many files in a single request and a single transfer to the agent. Each part's
filename is treated as a path relative to the destination directory, so folder structure is
preserved without client-side loops.

**Query parameters:**

| Param  | Required | Description                                    |
|--------|----------|------------------------------------------------|
| `path` | Yes      | Destination directory (created if missing)     |

**Request:** `Content-Type: multipart/form-data`, with one `files` part per file:

```text
files: filename="server.properties"
files: filename="plugins/MyPlugin/config.yml"
```

The destination of each part is `path` + the filename's relative segments. Absolute paths and
`..` segments are rejected with `400`. At most 500 files may be sent per request.

**Response `201`:**

```json
{
  "uploaded": 2,
  "failed": 1,
  "files": [
    { "path": "/upload/server.properties", "success": true, "size_bytes": 420 },
    { "path": "/upload/plugins/MyPlugin/config.yml", "success": true, "size_bytes": 97 },
    { "path": "/upload/locked/file.txt", "success": false, "size_bytes": 0, "error": "permission denied" }
  ]
}
```

A `201` only means the batch was delivered to the agent. Individual entries can still fail —
check each `files[].success`. A transport-level failure (agent disconnected, transfer aborted)
returns `502`; files completed before the failure stay on disk.

**Errors:** `400` if `path` is missing, a filename is absolute or contains `..`, or the batch
exceeds the per-request file limit. `502` if the transfer to the agent fails.

---

## `GET /servers/{id}/files/download`

Streams the file as an attachment.

**Query parameters:**

| Param  | Required | Description |
|--------|----------|-------------|
| `path` | Yes      | File path   |

**Response `200`:** binary stream with `Content-Disposition: attachment`.

**Errors:** `409` if the path is a directory (use backup export for full archives).

---

## `DELETE /servers/{id}/files`

**Query parameters:**

| Param       | Required | Description                                                        |
|-------------|----------|--------------------------------------------------------------------|
| `path`      | Yes      | File or directory path                                             |
| `recursive` | No       | `true` to delete a directory and all contents; defaults to `false` |

**Response `204`.**

**Errors:** `409` if the path is a non-empty directory and `recursive` is not `true`.

---

## `POST /servers/{id}/files/move`

Moves or renames a file or directory. Works across directories.

**Request:**

```json
{
  "source_path": "/plugins/old-name.jar",
  "destination_path": "/plugins/new-name.jar"
}
```

**Response `204`.**

**Errors:** `409` if the destination path already exists.

---

## `POST /servers/{id}/files/copy`

**Request:**

```json
{
  "source_path": "/plugins/myplugin.jar",
  "destination_path": "/plugins/myplugin-backup.jar",
  "recursive": false
}
```

Set `recursive` to `true` when copying a directory.

**Response `204`.**

**Errors:** `409` if the destination path already exists.

---

## `POST /servers/{id}/files/mkdir`

**Request:**

```json
{
  "path": "/plugins/MyPlugin/data"
}
```

Creates all intermediate directories as needed.

**Response `204`.**
