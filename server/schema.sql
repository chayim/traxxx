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

-- Workout templates: reusable programmes owned by a user.
CREATE TABLE IF NOT EXISTS workout_templates (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workout_templates_user ON workout_templates(user_id);

-- Ordered exercises inside a template. reps/seconds nullable so an
-- exercise can target reps, duration, or both.
CREATE TABLE IF NOT EXISTS template_exercises (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id    INTEGER NOT NULL REFERENCES workout_templates(id) ON DELETE CASCADE,
    position       INTEGER NOT NULL,
    name           TEXT NOT NULL,
    target_sets    INTEGER,
    target_reps    INTEGER,
    target_seconds INTEGER
);
CREATE INDEX IF NOT EXISTS idx_template_exercises_tpl ON template_exercises(template_id, position);

-- A dated workout performed by a user. template_id tracks provenance; nullable
-- so a workout can outlive its originating template.
CREATE TABLE IF NOT EXISTS workouts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_id  INTEGER REFERENCES workout_templates(id) ON DELETE SET NULL,
    name         TEXT NOT NULL,
    workout_date DATE NOT NULL,
    notes        TEXT,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workouts_user_date ON workouts(user_id, workout_date);

-- Exercises snapshotted into a workout at clone time.
CREATE TABLE IF NOT EXISTS workout_exercises (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    workout_id     INTEGER NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    position       INTEGER NOT NULL,
    name           TEXT NOT NULL,
    target_sets    INTEGER,
    target_reps    INTEGER,
    target_seconds INTEGER
);
CREATE INDEX IF NOT EXISTS idx_workout_exercises_wk ON workout_exercises(workout_id, position);

-- Actual sets performed per exercise.
CREATE TABLE IF NOT EXISTS workout_sets (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    exercise_id INTEGER NOT NULL REFERENCES workout_exercises(id) ON DELETE CASCADE,
    set_number  INTEGER NOT NULL,
    reps        INTEGER,
    seconds     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_workout_sets_ex ON workout_sets(exercise_id, set_number);
