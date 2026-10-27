package main

import (
	"context"
	"database/sql"
	"log"
	"math"
	"math/rand"
	"time"

	"traxxx/server/db/dbstore"
)

func seedFakeData(db *sql.DB, q *dbstore.Queries) {
	ctx := context.Background()
	log.Println("seeding fake data…")

	for _, fn := range []func(context.Context) error{
		q.SeedDeleteTrackPoints,
		q.SeedDeleteActivities,
		q.SeedDeleteWorkoutSets,
		q.SeedDeleteWorkoutExercises,
		q.SeedDeleteWorkouts,
		q.SeedDeleteTemplateExercises,
		q.SeedDeleteWorkoutTemplates,
		q.SeedDeleteUsers,
	} {
		if err := fn(ctx); err != nil {
			log.Fatalf("seed cleanup: %v", err)
		}
	}

	if err := q.SeedTypeIfMissing(ctx, "rollerblade"); err != nil {
		log.Fatalf("seed types: %v", err)
	}

	userID, err := q.CreateUser(ctx, "demo")
	if err != nil {
		log.Fatalf("seed user: %v", err)
	}

	typeIDs := map[string]int64{}
	for _, name := range []string{"walk", "rollerblade"} {
		id, err := q.GetTypeIDByName(ctx, name)
		if err != nil {
			log.Fatalf("seed types read: %v", err)
		}
		typeIDs[name] = id
	}

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

		kmCount := 10 + rng.Intn(6)
		basePace := 11.0 * 60.0
		if typeName == "rollerblade" {
			basePace = 4.5 * 60.0
		}

		kmSecs := make([]float64, kmCount)
		var totalSecs float64
		for k := 0; k < kmCount; k++ {
			variance := 1.0 + (rng.Float64()*0.7 - 0.35)
			kmSecs[k] = basePace * variance
			totalSecs += kmSecs[k]
		}
		stoppedAt := startedAt.Add(time.Duration(totalSecs * float64(time.Second)))

		actID, err := q.CreateActivity(ctx, dbstore.CreateActivityParams{
			UserID:       userID,
			TypeID:       typeID,
			Name:         activityNames[rng.Intn(len(activityNames))],
			ActivityDate: date.Format("2006-01-02"),
			StartedAt:    sql.NullTime{Time: startedAt, Valid: true},
			StoppedAt:    sql.NullTime{Time: stoppedAt, Valid: true},
			TotalSeconds: sql.NullInt64{Int64: int64(totalSecs), Valid: true},
		})
		if err != nil {
			log.Fatalf("seed activity: %v", err)
		}

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
		qTx := q.WithTx(tx)
		if err := qTx.CreateTrackPointBasic(ctx, dbstore.CreateTrackPointBasicParams{
			ActivityID: actID, RecordedAt: t, Lat: lat, Lon: lon,
		}); err != nil {
			log.Fatalf("seed point: %v", err)
		}
		for k := 0; k < kmCount; k++ {
			segSecs := kmSecs[k] / float64(pointsPerKm)
			segMeters := 1000.0 / float64(pointsPerKm)
			for p := 0; p < pointsPerKm; p++ {
				heading += (rng.Float64() - 0.5) * 0.4
				dLat := math.Cos(heading) * segMeters / 111000.0
				dLon := math.Sin(heading) * segMeters / (111000.0 * math.Cos(lat*math.Pi/180))
				lat += dLat
				lon += dLon
				t = t.Add(time.Duration(segSecs * float64(time.Second)))
				if err := qTx.CreateTrackPointBasic(ctx, dbstore.CreateTrackPointBasicParams{
					ActivityID: actID, RecordedAt: t, Lat: lat, Lon: lon,
				}); err != nil {
					log.Fatalf("seed point: %v", err)
				}
			}
		}
		if err := tx.Commit(); err != nil {
			log.Fatalf("seed commit: %v", err)
		}
	}

	seedWorkouts(ctx, q, userID, rng)
	log.Println("seed complete: 100 activities, 3 templates, workouts; user 'demo'")
}

type seedExercise struct {
	Name    string
	Sets    int
	Reps    int
	Seconds int
}

type seedTemplate struct {
	Name      string
	Exercises []seedExercise
}

func seedWorkouts(ctx context.Context, q *dbstore.Queries, userID int64, rng *rand.Rand) {
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
		tid, err := q.CreateWorkoutTemplate(ctx, dbstore.CreateWorkoutTemplateParams{
			UserID: userID, Name: t.Name,
		})
		if err != nil {
			log.Fatalf("seed template: %v", err)
		}
		templateIDs = append(templateIDs, tid)
		for pos, ex := range t.Exercises {
			if err := q.CreateTemplateExercise(ctx, dbstore.CreateTemplateExerciseParams{
				TemplateID:    tid,
				Position:      int64(pos + 1),
				Name:          ex.Name,
				TargetSets:    sql.NullInt64{Int64: int64(ex.Sets), Valid: true},
				TargetReps:    nullIntValue(ex.Reps),
				TargetSeconds: nullIntValue(ex.Seconds),
			}); err != nil {
				log.Fatalf("seed template exercise: %v", err)
			}
		}
	}

	start := time.Now().AddDate(0, 0, -90).Truncate(24 * time.Hour)
	for i := 0; i < 30; i++ {
		tplIdx := i % len(templates)
		tpl := templates[tplIdx]
		tplID := templateIDs[tplIdx]
		date := start.AddDate(0, 0, i*3)

		wid, err := q.CreateWorkoutMinimal(ctx, dbstore.CreateWorkoutMinimalParams{
			UserID:      userID,
			TemplateID:  sql.NullInt64{Int64: tplID, Valid: true},
			Name:        tpl.Name,
			WorkoutDate: date.Format("2006-01-02"),
		})
		if err != nil {
			log.Fatalf("seed workout: %v", err)
		}

		for pos, ex := range tpl.Exercises {
			eid, err := q.CreateWorkoutExercise(ctx, dbstore.CreateWorkoutExerciseParams{
				WorkoutID:     wid,
				Position:      int64(pos + 1),
				Name:          ex.Name,
				TargetSets:    sql.NullInt64{Int64: int64(ex.Sets), Valid: true},
				TargetReps:    nullIntValue(ex.Reps),
				TargetSeconds: nullIntValue(ex.Seconds),
			})
			if err != nil {
				log.Fatalf("seed workout exercise: %v", err)
			}
			minSets := 2
			if ex.Sets < minSets {
				minSets = ex.Sets
			}
			nSets := minSets
			if ex.Sets > minSets {
				nSets = minSets + rng.Intn(ex.Sets-minSets+1)
			}
			for s := 0; s < nSets; s++ {
				var reps, seconds sql.NullInt64
				if ex.Reps > 0 {
					reps = sql.NullInt64{Int64: int64(jitter(ex.Reps, rng)), Valid: true}
				}
				if ex.Seconds > 0 {
					seconds = sql.NullInt64{Int64: int64(jitter(ex.Seconds, rng)), Valid: true}
				}
				if err := q.CreateWorkoutSet(ctx, dbstore.CreateWorkoutSetParams{
					ExerciseID: eid, SetNumber: int64(s + 1),
					Reps: reps, Seconds: seconds,
				}); err != nil {
					log.Fatalf("seed set: %v", err)
				}
			}
		}
	}
}

func nullIntValue(n int) sql.NullInt64 {
	if n == 0 {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: int64(n), Valid: true}
}

func jitter(base int, rng *rand.Rand) int {
	v := float64(base) * (0.8 + rng.Float64()*0.4)
	if v < 1 {
		v = 1
	}
	return int(v)
}
