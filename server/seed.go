package main

import (
	"database/sql"
	"log"
	"math"
	"math/rand"
	"time"
)

func seedFakeData(db *sql.DB) {
	log.Println("seeding fake data…")

	// Clean out existing fake data so re-seeding is idempotent.
	// Order respects FK cascades, but we delete leaves explicitly to be safe
	// if cascades were ever disabled.
	for _, q := range []string{
		`DELETE FROM track_points`,
		`DELETE FROM activities`,
		`DELETE FROM workout_sets`,
		`DELETE FROM workout_exercises`,
		`DELETE FROM workouts`,
		`DELETE FROM template_exercises`,
		`DELETE FROM workout_templates`,
		`DELETE FROM users`,
	} {
		if _, err := db.Exec(q); err != nil {
			log.Fatalf("seed cleanup: %v", err)
		}
	}

	// Ensure rollerblade exists alongside walk.
	if _, err := db.Exec(`INSERT OR IGNORE INTO activity_types (name) VALUES ('rollerblade')`); err != nil {
		log.Fatalf("seed types: %v", err)
	}

	// One user to view all this data.
	res, err := db.Exec(`INSERT INTO users (name) VALUES ('demo')`)
	if err != nil {
		log.Fatalf("seed user: %v", err)
	}
	userID, _ := res.LastInsertId()

	typeIDs := map[string]int64{}
	rows, err := db.Query(`SELECT id, name FROM activity_types WHERE name IN ('walk','rollerblade')`)
	if err != nil {
		log.Fatalf("seed types read: %v", err)
	}
	for rows.Next() {
		var id int64
		var name string
		_ = rows.Scan(&id, &name)
		typeIDs[name] = id
	}
	rows.Close()

	rng := rand.New(rand.NewSource(42))
	activityNames := []string{
		"Morning loop", "Lunch stroll", "Lakeside circuit", "Evening cruise",
		"River path", "Downtown roll", "Park perimeter", "Harbor route",
		"Sunset session", "Weekend wander", "Trail blast", "Pier run",
	}

	start := time.Now().AddDate(0, 0, -200).Truncate(24 * time.Hour)
	for i := 0; i < 100; i++ {
		typeName := "walk"
		if rng.Intn(2) == 0 {
			typeName = "rollerblade"
		}
		typeID := typeIDs[typeName]
		dayOffset := i * 2
		date := start.AddDate(0, 0, dayOffset)
		startedAt := date.Add(time.Duration(6+rng.Intn(12)) * time.Hour)

		kmCount := 10 + rng.Intn(6) // 10..15
		// Baseline seconds/km by type.
		basePace := 11.0 * 60.0 // walk: ~11 min/km
		if typeName == "rollerblade" {
			basePace = 4.5 * 60.0
		}

		// Pick each km's seconds, varying ±35%.
		kmSecs := make([]float64, kmCount)
		var totalSecs float64
		for k := 0; k < kmCount; k++ {
			variance := 1.0 + (rng.Float64()*0.7 - 0.35)
			kmSecs[k] = basePace * variance
			totalSecs += kmSecs[k]
		}
		stoppedAt := startedAt.Add(time.Duration(totalSecs * float64(time.Second)))

		res, err := db.Exec(`INSERT INTO activities
			(user_id, type_id, name, activity_date, started_at, stopped_at, total_seconds)
			VALUES (?, ?, ?, ?, ?, ?, ?)`,
			userID, typeID,
			activityNames[rng.Intn(len(activityNames))],
			date.Format("2006-01-02"),
			startedAt, stoppedAt, int64(totalSecs))
		if err != nil {
			log.Fatalf("seed activity: %v", err)
		}
		actID, _ := res.LastInsertId()

		// Generate GPS track: wander around a city center.
		centerLat := 40.7128 + rng.Float64()*0.02
		centerLon := -74.006 + rng.Float64()*0.02
		pointsPerKm := 20
		heading := rng.Float64() * 2 * math.Pi
		lat, lon := centerLat, centerLon
		t := startedAt

		tx, err := db.Begin()
		if err != nil {
			log.Fatalf("seed tx: %v", err)
		}
		stmt, err := tx.Prepare(`INSERT INTO track_points
			(activity_id, recorded_at, lat, lon) VALUES (?, ?, ?, ?)`)
		if err != nil {
			log.Fatalf("seed stmt: %v", err)
		}
		// First point.
		if _, err := stmt.Exec(actID, t, lat, lon); err != nil {
			log.Fatalf("seed point: %v", err)
		}
		for k := 0; k < kmCount; k++ {
			segSecs := kmSecs[k] / float64(pointsPerKm)
			segMeters := 1000.0 / float64(pointsPerKm)
			for p := 0; p < pointsPerKm; p++ {
				heading += (rng.Float64() - 0.5) * 0.4
				// ~1 degree latitude ≈ 111_000 m.
				dLat := math.Cos(heading) * segMeters / 111000.0
				dLon := math.Sin(heading) * segMeters / (111000.0 * math.Cos(lat*math.Pi/180))
				lat += dLat
				lon += dLon
				t = t.Add(time.Duration(segSecs * float64(time.Second)))
				if _, err := stmt.Exec(actID, t, lat, lon); err != nil {
					log.Fatalf("seed point: %v", err)
				}
			}
		}
		stmt.Close()
		if err := tx.Commit(); err != nil {
			log.Fatalf("seed commit: %v", err)
		}
	}

	seedWorkouts(db, userID, rng)
	log.Println("seed complete: 100 activities, 3 templates, workouts; user 'demo'")
}

type seedExercise struct {
	Name    string
	Sets    int
	Reps    int // 0 = no rep target
	Seconds int // 0 = no duration target
}

type seedTemplate struct {
	Name      string
	Exercises []seedExercise
}

func seedWorkouts(db *sql.DB, userID int64, rng *rand.Rand) {
	templates := []seedTemplate{
		{Name: "Push day", Exercises: []seedExercise{
			{Name: "Bench Press", Sets: 4, Reps: 8},
			{Name: "Overhead Press", Sets: 3, Reps: 10},
			{Name: "Dips", Sets: 3, Reps: 12},
			{Name: "Plank", Sets: 3, Seconds: 60},
		}},
		{Name: "Pull day", Exercises: []seedExercise{
			{Name: "Deadlift", Sets: 4, Reps: 5},
			{Name: "Pull-ups", Sets: 4, Reps: 8},
			{Name: "Barbell Row", Sets: 3, Reps: 10},
			{Name: "Face Pulls", Sets: 3, Reps: 15},
		}},
		{Name: "Legs & core", Exercises: []seedExercise{
			{Name: "Squat", Sets: 5, Reps: 5},
			{Name: "Lunges", Sets: 3, Reps: 10},
			{Name: "Calf Raises", Sets: 4, Reps: 15},
			{Name: "Hanging Leg Raise", Sets: 3, Reps: 12},
			{Name: "Wall Sit", Sets: 3, Seconds: 45},
		}},
	}

	templateIDs := make([]int64, 0, len(templates))
	for _, t := range templates {
		res, err := db.Exec(`INSERT INTO workout_templates (user_id, name) VALUES (?, ?)`, userID, t.Name)
		if err != nil {
			log.Fatalf("seed template: %v", err)
		}
		tid, _ := res.LastInsertId()
		templateIDs = append(templateIDs, tid)
		for pos, ex := range t.Exercises {
			if _, err := db.Exec(`INSERT INTO template_exercises
				(template_id, position, name, target_sets, target_reps, target_seconds)
				VALUES (?, ?, ?, ?, ?, ?)`,
				tid, pos+1, ex.Name, ex.Sets, nullIfZero(ex.Reps), nullIfZero(ex.Seconds)); err != nil {
				log.Fatalf("seed template exercise: %v", err)
			}
		}
	}

	// Generate ~30 dated workouts cycling through templates over the previous 90 days.
	start := time.Now().AddDate(0, 0, -90).Truncate(24 * time.Hour)
	for i := 0; i < 30; i++ {
		tplIdx := i % len(templates)
		tpl := templates[tplIdx]
		tplID := templateIDs[tplIdx]
		date := start.AddDate(0, 0, i*3)

		res, err := db.Exec(`INSERT INTO workouts (user_id, template_id, name, workout_date)
			VALUES (?, ?, ?, ?)`,
			userID, tplID, tpl.Name, date.Format("2006-01-02"))
		if err != nil {
			log.Fatalf("seed workout: %v", err)
		}
		wid, _ := res.LastInsertId()

		for pos, ex := range tpl.Exercises {
			res, err := db.Exec(`INSERT INTO workout_exercises
				(workout_id, position, name, target_sets, target_reps, target_seconds)
				VALUES (?, ?, ?, ?, ?, ?)`,
				wid, pos+1, ex.Name, ex.Sets, nullIfZero(ex.Reps), nullIfZero(ex.Seconds))
			if err != nil {
				log.Fatalf("seed workout exercise: %v", err)
			}
			eid, _ := res.LastInsertId()
			// Perform between 2 and target_sets sets, jittered ±20% on reps/seconds.
			minSets := 2
			if ex.Sets < minSets {
				minSets = ex.Sets
			}
			nSets := minSets
			if ex.Sets > minSets {
				nSets = minSets + rng.Intn(ex.Sets-minSets+1)
			}
			for s := 0; s < nSets; s++ {
				var reps, seconds any
				if ex.Reps > 0 {
					reps = jitter(ex.Reps, rng)
				}
				if ex.Seconds > 0 {
					seconds = jitter(ex.Seconds, rng)
				}
				if _, err := db.Exec(`INSERT INTO workout_sets (exercise_id, set_number, reps, seconds)
					VALUES (?, ?, ?, ?)`, eid, s+1, reps, seconds); err != nil {
					log.Fatalf("seed set: %v", err)
				}
			}
		}
	}
}

func nullIfZero(n int) any {
	if n == 0 {
		return nil
	}
	return n
}

func jitter(base int, rng *rand.Rand) int {
	v := float64(base) * (0.8 + rng.Float64()*0.4)
	if v < 1 {
		v = 1
	}
	return int(v)
}
