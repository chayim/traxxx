# traxxx

Self-hosted Strava-ish activity tracker. Loosely inspired by OwnTracks.

- **server/** — Go REST API, SQLite3 storage.
- **web/** — Node/Express app serving a small SPA that proxies to the API.

No passwords: users are just selectable identities.

## Server

```
cd server
go mod tidy
go run . --seed --db traxxx.db     # one-shot: wipes and seeds 15 fake activities
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

## Web

```
cd web
npm install
API_BASE=http://localhost:8080 npm start   # http://localhost:3000
```

Pick a user, filter activities by date range and/or type.
