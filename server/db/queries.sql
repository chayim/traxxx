-- Shared query file. Generated for both postgres and sqlite via sqlc.
-- Uses sqlc.arg / sqlc.narg for named params (portable across both engines).
-- IN-clauses use sqlc.slice to expand to the right number of placeholders.

-- =========================================================================
-- users
-- =========================================================================

-- name: ListUsers :many
SELECT id, name, created_at FROM users ORDER BY name;

-- name: FirstUserID :one
SELECT id FROM users ORDER BY id LIMIT 1;

-- name: CreateUser :one
INSERT INTO users (name) VALUES (sqlc.arg('name')) RETURNING id;

-- =========================================================================
-- activity_types
-- =========================================================================

-- name: ListTypes :many
SELECT id, name, retired FROM activity_types
WHERE (CAST(sqlc.narg('include_retired') AS BIGINT) = 1 OR retired = 0)
ORDER BY name;

-- name: CreateType :one
INSERT INTO activity_types (name) VALUES (sqlc.arg('name')) RETURNING id;

-- name: SeedTypeIfMissing :exec
INSERT INTO activity_types (name) VALUES (sqlc.arg('name')) ON CONFLICT (name) DO NOTHING;

-- name: GetTypeIDByName :one
SELECT id FROM activity_types WHERE name = sqlc.arg('name');

-- =========================================================================
-- activities
-- =========================================================================

-- name: ListActivities :many
SELECT a.id, a.user_id, a.type_id, t.name AS type_name, a.name, a.activity_date,
       a.started_at, a.stopped_at, a.total_seconds,
       COALESCE(a.notes,'') AS notes,
       COALESCE(a.client_uuid,'') AS client_uuid,
       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id) AS point_count
FROM activities a
JOIN activity_types t ON t.id = a.type_id
WHERE (CAST(sqlc.narg('user_id') AS BIGINT) IS NULL OR a.user_id = sqlc.narg('user_id'))
  AND (CAST(sqlc.narg('type_id') AS BIGINT) IS NULL OR a.type_id = sqlc.narg('type_id'))
  AND (CAST(sqlc.narg('type_name') AS TEXT) IS NULL OR t.name = sqlc.narg('type_name'))
  AND (CAST(sqlc.narg('date_from') AS TEXT) IS NULL OR a.activity_date >= sqlc.narg('date_from'))
  AND (CAST(sqlc.narg('date_to') AS TEXT) IS NULL OR a.activity_date <= sqlc.narg('date_to'))
ORDER BY a.activity_date DESC, a.id DESC;

-- name: ListActivitiesForUserUI :many
SELECT a.id, a.name, a.activity_date, t.name AS type_name,
       a.total_seconds,
       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id) AS point_count
FROM activities a JOIN activity_types t ON t.id = a.type_id
WHERE a.user_id = sqlc.arg('user_id')
ORDER BY a.activity_date DESC, a.id DESC LIMIT 200;

-- name: GetActivity :one
SELECT a.id, a.user_id, a.type_id, t.name AS type_name, a.name, a.activity_date,
       a.started_at, a.stopped_at, a.total_seconds,
       COALESCE(a.notes,'') AS notes,
       COALESCE(a.client_uuid,'') AS client_uuid,
       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id) AS point_count
FROM activities a JOIN activity_types t ON t.id = a.type_id
WHERE a.id = sqlc.arg('id');

-- name: GetActivityForUI :one
SELECT a.id, a.user_id, a.name, a.activity_date, t.name AS type_name,
       COALESCE(a.notes,'') AS notes, a.total_seconds,
       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id) AS point_count
FROM activities a JOIN activity_types t ON t.id = a.type_id
WHERE a.id = sqlc.arg('id');

-- name: FindActivityByClientUUID :one
SELECT id FROM activities WHERE client_uuid = sqlc.arg('client_uuid') LIMIT 1;

-- name: CreateActivity :one
INSERT INTO activities
  (user_id, type_id, name, activity_date, started_at, stopped_at, total_seconds, notes, client_uuid)
VALUES (
  sqlc.arg('user_id'), sqlc.arg('type_id'), sqlc.arg('name'), sqlc.arg('activity_date'),
  sqlc.narg('started_at'), sqlc.narg('stopped_at'), sqlc.narg('total_seconds'),
  sqlc.narg('notes'), sqlc.narg('client_uuid')
) RETURNING id;

-- name: DeleteActivity :exec
DELETE FROM activities WHERE id = sqlc.arg('id');

-- name: SiblingActivityIDs :many
SELECT id FROM activities
WHERE user_id = sqlc.arg('user_id') AND type_id = sqlc.arg('type_id') AND id != sqlc.arg('exclude_id');

-- =========================================================================
-- track_points
-- =========================================================================

-- name: ListTrackPoints :many
SELECT recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps
FROM track_points WHERE activity_id = sqlc.arg('activity_id') ORDER BY recorded_at;

-- Loop in Go for the multi-activity case.

-- name: CreateTrackPoint :exec
INSERT INTO track_points
  (activity_id, recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps)
VALUES (
  sqlc.arg('activity_id'), sqlc.arg('recorded_at'), sqlc.arg('lat'), sqlc.arg('lon'),
  sqlc.narg('elevation_m'), sqlc.narg('accuracy_m'), sqlc.narg('speed_mps')
);

-- name: CreateTrackPointBasic :exec
INSERT INTO track_points (activity_id, recorded_at, lat, lon)
VALUES (sqlc.arg('activity_id'), sqlc.arg('recorded_at'), sqlc.arg('lat'), sqlc.arg('lon'));

-- =========================================================================
-- workout_templates
-- =========================================================================

-- name: ListWorkoutTemplates :many
SELECT id, user_id, name, created_at FROM workout_templates
WHERE (CAST(sqlc.narg('user_id') AS BIGINT) IS NULL OR user_id = sqlc.narg('user_id'))
ORDER BY name;

-- name: ListTemplatesWithExerciseCount :many
SELECT t.id, t.name,
       (SELECT COUNT(*) FROM template_exercises e WHERE e.template_id = t.id) AS exercise_count
FROM workout_templates t WHERE t.user_id = sqlc.arg('user_id') ORDER BY t.name;

-- name: GetWorkoutTemplate :one
SELECT id, user_id, name, created_at FROM workout_templates WHERE id = sqlc.arg('id');

-- name: CreateWorkoutTemplate :one
INSERT INTO workout_templates (user_id, name)
VALUES (sqlc.arg('user_id'), sqlc.arg('name')) RETURNING id;

-- name: UpdateWorkoutTemplate :exec
UPDATE workout_templates SET name = sqlc.arg('name') WHERE id = sqlc.arg('id');

-- name: DeleteWorkoutTemplate :exec
DELETE FROM workout_templates WHERE id = sqlc.arg('id');

-- =========================================================================
-- template_exercises
-- =========================================================================

-- name: ListTemplateExercises :many
SELECT id, template_id, position, name, target_sets, target_reps, target_seconds
FROM template_exercises
WHERE template_id = sqlc.arg('template_id')
ORDER BY position;

-- name: CreateTemplateExercise :exec
INSERT INTO template_exercises
  (template_id, position, name, target_sets, target_reps, target_seconds)
VALUES (
  sqlc.arg('template_id'), sqlc.arg('position'), sqlc.arg('name'),
  sqlc.narg('target_sets'), sqlc.narg('target_reps'), sqlc.narg('target_seconds')
);

-- name: DeleteTemplateExercisesForTemplate :exec
DELETE FROM template_exercises WHERE template_id = sqlc.arg('template_id');

-- =========================================================================
-- workouts
-- =========================================================================

-- name: ListWorkouts :many
SELECT id, user_id, template_id, name, workout_date,
       COALESCE(notes,'') AS notes,
       COALESCE(client_uuid,'') AS client_uuid, created_at FROM workouts
WHERE (CAST(sqlc.narg('user_id') AS BIGINT) IS NULL OR user_id = sqlc.narg('user_id'))
  AND (CAST(sqlc.narg('date_from') AS TEXT) IS NULL OR workout_date >= sqlc.narg('date_from'))
  AND (CAST(sqlc.narg('date_to') AS TEXT) IS NULL OR workout_date <= sqlc.narg('date_to'))
ORDER BY workout_date DESC, id DESC;

-- name: ListWorkoutsForUserUI :many
SELECT w.id, w.name, w.workout_date,
       (SELECT COUNT(*) FROM workout_exercises e WHERE e.workout_id = w.id) AS exercise_count
FROM workouts w
WHERE w.user_id = sqlc.arg('user_id') ORDER BY w.workout_date DESC, w.id DESC;

-- name: GetWorkout :one
SELECT id, user_id, template_id, name, workout_date,
       COALESCE(notes,'') AS notes,
       COALESCE(client_uuid,'') AS client_uuid, created_at FROM workouts WHERE id = sqlc.arg('id');

-- name: FindWorkoutByClientUUID :one
SELECT id FROM workouts WHERE client_uuid = sqlc.arg('client_uuid') LIMIT 1;

-- name: CreateWorkout :one
INSERT INTO workouts (user_id, template_id, name, workout_date, notes, client_uuid)
VALUES (
  sqlc.arg('user_id'), sqlc.narg('template_id'), sqlc.arg('name'),
  sqlc.arg('workout_date'), sqlc.narg('notes'), sqlc.narg('client_uuid')
) RETURNING id;

-- name: CreateWorkoutMinimal :one
INSERT INTO workouts (user_id, template_id, name, workout_date)
VALUES (
  sqlc.arg('user_id'), sqlc.narg('template_id'), sqlc.arg('name'), sqlc.arg('workout_date')
) RETURNING id;

-- name: UpdateWorkout :exec
UPDATE workouts
SET name = sqlc.arg('name'), workout_date = sqlc.arg('workout_date'), notes = sqlc.arg('notes')
WHERE id = sqlc.arg('id');

-- name: UpdateWorkoutPartial :exec
UPDATE workouts SET
  name         = COALESCE(NULLIF(sqlc.arg('name'), ''), name),
  workout_date = COALESCE(NULLIF(sqlc.arg('workout_date'), ''), workout_date),
  notes        = sqlc.arg('notes')
WHERE id = sqlc.arg('id');

-- name: DeleteWorkout :exec
DELETE FROM workouts WHERE id = sqlc.arg('id');

-- =========================================================================
-- workout_exercises
-- =========================================================================

-- name: ListWorkoutExercises :many
SELECT id, workout_id, position, name, target_sets, target_reps, target_seconds
FROM workout_exercises WHERE workout_id = sqlc.arg('workout_id') ORDER BY position;

-- name: CreateWorkoutExercise :one
INSERT INTO workout_exercises
  (workout_id, position, name, target_sets, target_reps, target_seconds)
VALUES (
  sqlc.arg('workout_id'), sqlc.arg('position'), sqlc.arg('name'),
  sqlc.narg('target_sets'), sqlc.narg('target_reps'), sqlc.narg('target_seconds')
) RETURNING id;

-- name: DeleteWorkoutExercisesForWorkout :exec
DELETE FROM workout_exercises WHERE workout_id = sqlc.arg('workout_id');

-- =========================================================================
-- workout_sets
-- =========================================================================

-- name: ListWorkoutSetsForExercise :many
SELECT id, exercise_id, set_number, reps, seconds
FROM workout_sets
WHERE exercise_id = sqlc.arg('exercise_id')
ORDER BY set_number;

-- name: CreateWorkoutSet :exec
INSERT INTO workout_sets (exercise_id, set_number, reps, seconds)
VALUES (sqlc.arg('exercise_id'), sqlc.arg('set_number'), sqlc.narg('reps'), sqlc.narg('seconds'));

-- =========================================================================
-- seed cleanup
-- =========================================================================

-- name: SeedDeleteTrackPoints :exec
DELETE FROM track_points;

-- name: SeedDeleteActivities :exec
DELETE FROM activities;

-- name: SeedDeleteWorkoutSets :exec
DELETE FROM workout_sets;

-- name: SeedDeleteWorkoutExercises :exec
DELETE FROM workout_exercises;

-- name: SeedDeleteWorkouts :exec
DELETE FROM workouts;

-- name: SeedDeleteTemplateExercises :exec
DELETE FROM template_exercises;

-- name: SeedDeleteWorkoutTemplates :exec
DELETE FROM workout_templates;

-- name: SeedDeleteUsers :exec
DELETE FROM users;
