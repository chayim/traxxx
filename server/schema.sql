-- Users have no passwords; they're just a selectable identity.
CREATE TABLE IF NOT EXISTS users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Activity types are mutable over time (add/rename/retire).
CREATE TABLE IF NOT EXISTS activity_types (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name     TEXT NOT NULL UNIQUE,
    retired  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS activities (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type_id         INTEGER NOT NULL REFERENCES activity_types(id),
    name            TEXT NOT NULL,
    activity_date   DATE NOT NULL,
    started_at      DATETIME,
    stopped_at      DATETIME,
    total_seconds   INTEGER,
    notes           TEXT,
    client_uuid     TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_activities_user_date ON activities(user_id, activity_date);
CREATE INDEX IF NOT EXISTS idx_activities_type ON activities(type_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_activities_client_uuid
    ON activities(client_uuid) WHERE client_uuid IS NOT NULL;

-- GPS points for activities. Only present when a track was recorded.
CREATE TABLE IF NOT EXISTS track_points (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    activity_id  INTEGER NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    recorded_at  DATETIME NOT NULL,
    lat          REAL NOT NULL,
    lon          REAL NOT NULL,
    elevation_m  REAL,
    accuracy_m   REAL,
    speed_mps    REAL
);
CREATE INDEX IF NOT EXISTS idx_track_points_activity ON track_points(activity_id, recorded_at);

-- Seed a few default activity types.
INSERT OR IGNORE INTO activity_types (name) VALUES
    ('run'), ('ride'), ('walk'), ('hike'), ('swim'), ('other');
