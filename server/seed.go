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
	for _, q := range []string{
		`DELETE FROM track_points`,
		`DELETE FROM activities`,
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

	start := time.Now().AddDate(0, 0, -30).Truncate(24 * time.Hour)
	for i := 0; i < 15; i++ {
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

	log.Println("seed complete: 15 activities, user 'demo'")
}
