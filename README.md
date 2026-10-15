# traxxx

Self-hosted Strava-ish activity tracker. Loosely inspired by OwnTracks.

- **server/** — Go REST API, SQLite3 storage; also serves the embedded web
  SPA (single-binary deploy).
- **android/** — Native Kotlin/Jetpack Compose client with foreground GPS
  recording and background sync.

No passwords: users are just selectable identities.

## Server

```
cd server
go mod tidy
go run . --seed --db traxxx.db     # one-shot: wipes and seeds 100 fake activities
go run . --addr :8080 --db traxxx.db
```

### Data model

- `users (id, name, created_at)`
- `activity_types (id, name, retired)` — mutable over time; retire with `retired = 1` rather than deleting.
- `activities (id, user_id, type_id, name, activity_date, started_at, stopped_at, total_seconds, notes, created_at)`
- `track_points (id, activity_id, recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps)` — only present for activities with recorded GPS.

### REST

| Method | Path | Notes |
|---|---|---|
| GET  | `/api/users` | list |
| POST | `/api/users` | `{ "name": "..." }` |
| GET  | `/api/types` | `?all=1` includes retired |
| POST | `/api/types` | `{ "name": "..." }` |
| GET  | `/api/activities` | filter by `user_id`, `type_id` or `type` (name), `from`/`to` (YYYY-MM-DD) |
| POST | `/api/activities` | create (optionally with `track: [...]`) |
| GET  | `/api/activities/{id}` | one |
| DELETE | `/api/activities/{id}` | delete |
| GET  | `/api/activities/{id}/track` | GPS points |

Example create:

```json
POST /api/activities
{
  "user_id": 1, "type_id": 1, "name": "Morning run", "activity_date": "2026-10-15",
  "started_at": "2026-10-15T06:30:00Z", "stopped_at": "2026-10-15T07:12:00Z",
  "track": [{"recorded_at":"2026-10-15T06:30:01Z","lat":40.1,"lon":-74.0}]
}
```

The web SPA lives in `server/web/` (HTML + JS + CSS) and is embedded into the
Go binary via `//go:embed`. When the server runs it serves the SPA at `/`
alongside the `/api/*` endpoints, so one binary ships everything. Point a
browser at `http://localhost:8080/` and pick a user.

## Docker

A `server/Dockerfile` produces a single-binary image (no CGO, pure-Go SQLite).
SQLite data lives in `/data`, which should be mounted as a volume.

```
make docker-build DOCKER_IMAGE=yourhubuser/traxxx-server DOCKER_TAG=0.1.0
docker run -d --name traxxx -p 8080:8080 -v traxxx-data:/data \
  yourhubuser/traxxx-server:0.1.0
```

## Android

Native Kotlin app (Gradle, Jetpack Compose). Records activities to a local
Room DB, pushes to the server via a `WorkManager` job, and keeps GPS running
through a foreground service that survives app kills and reboots. Each upload
carries a client-generated `client_uuid` so retries are idempotent.

```
make android-wrapper    # one-time: writes gradle-wrapper.jar (needs system `gradle`)
make android-dev        # build, install, launch on the connected device/emulator
make android-logs       # follow service + sync logs
```

Emulator: the default API base is `http://10.0.2.2:8080` (the host-machine
loopback as seen from the AVD). Cleartext HTTP is enabled by default; for
release builds point `apiBase` at an `https://` server.

Build toolchain: JDK 17, Android SDK platform 34, build-tools 34.0.0+.
Set `ANDROID_HOME` (and `JAVA_HOME` if your `java` isn't 17) before building.

### Top-level `make help`

Shows every target: `server`, `server-run`, `server-seed`, `docker-build`,
`android-wrapper`, `android-build`, `android-install`, `android-dev`,
`android-logs`, `android-stop`, `clean`.
