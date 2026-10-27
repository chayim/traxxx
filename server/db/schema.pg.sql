CREATE TABLE IF NOT EXISTS users (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS activity_types (
    id       BIGSERIAL PRIMARY KEY,
    name     TEXT NOT NULL UNIQUE,
    retired  BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS activities (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type_id         BIGINT NOT NULL REFERENCES activity_types(id),
    name            TEXT NOT NULL,
    activity_date   TEXT NOT NULL,
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    total_seconds   BIGINT,
    notes           TEXT,
    client_uuid     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_activities_user_date ON activities(user_id, activity_date);
CREATE INDEX IF NOT EXISTS idx_activities_type ON activities(type_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_activities_client_uuid
    ON activities(client_uuid) WHERE client_uuid IS NOT NULL;

CREATE TABLE IF NOT EXISTS track_points (
    id           BIGSERIAL PRIMARY KEY,
    activity_id  BIGINT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    recorded_at  TIMESTAMPTZ NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lon          DOUBLE PRECISION NOT NULL,
    elevation_m  DOUBLE PRECISION,
    accuracy_m   DOUBLE PRECISION,
    speed_mps    DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_track_points_activity ON track_points(activity_id, recorded_at);

CREATE TABLE IF NOT EXISTS workout_templates (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workout_templates_user ON workout_templates(user_id);

CREATE TABLE IF NOT EXISTS template_exercises (
    id             BIGSERIAL PRIMARY KEY,
    template_id    BIGINT NOT NULL REFERENCES workout_templates(id) ON DELETE CASCADE,
    position       BIGINT NOT NULL,
    name           TEXT NOT NULL,
    target_sets    BIGINT,
    target_reps    BIGINT,
    target_seconds BIGINT
);
CREATE INDEX IF NOT EXISTS idx_template_exercises_tpl ON template_exercises(template_id, position);

CREATE TABLE IF NOT EXISTS workouts (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_id  BIGINT REFERENCES workout_templates(id) ON DELETE SET NULL,
    name         TEXT NOT NULL,
    workout_date TEXT NOT NULL,
    notes        TEXT,
    client_uuid  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workouts_user_date ON workouts(user_id, workout_date);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workouts_client_uuid
    ON workouts(client_uuid) WHERE client_uuid IS NOT NULL;

CREATE TABLE IF NOT EXISTS workout_exercises (
    id             BIGSERIAL PRIMARY KEY,
    workout_id     BIGINT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    position       BIGINT NOT NULL,
    name           TEXT NOT NULL,
    target_sets    BIGINT,
    target_reps    BIGINT,
    target_seconds BIGINT
);
CREATE INDEX IF NOT EXISTS idx_workout_exercises_wk ON workout_exercises(workout_id, position);

CREATE TABLE IF NOT EXISTS workout_sets (
    id          BIGSERIAL PRIMARY KEY,
    exercise_id BIGINT NOT NULL REFERENCES workout_exercises(id) ON DELETE CASCADE,
    set_number  BIGINT NOT NULL,
    reps        BIGINT,
    seconds     BIGINT
);
CREATE INDEX IF NOT EXISTS idx_workout_sets_ex ON workout_sets(exercise_id, set_number);

-- Seed default activity types (idempotent).
INSERT INTO activity_types (name) VALUES
    ('run'), ('ride'), ('walk'), ('hike'), ('swim'), ('other')
    ON CONFLICT (name) DO NOTHING;
