package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type Server struct {
	DB *sql.DB
}

type User struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"created_at"`
}

type ActivityType struct {
	ID      int64  `json:"id"`
	Name    string `json:"name"`
	Retired bool   `json:"retired"`
}

type Activity struct {
	ID            int64      `json:"id"`
	UserID        int64      `json:"user_id"`
	TypeID        int64      `json:"type_id"`
	TypeName      string     `json:"type_name,omitempty"`
	Name          string     `json:"name"`
	ActivityDate  string     `json:"activity_date"` // YYYY-MM-DD
	StartedAt     *time.Time `json:"started_at,omitempty"`
	StoppedAt     *time.Time `json:"stopped_at,omitempty"`
	TotalSeconds  *int64     `json:"total_seconds,omitempty"`
	Notes         string     `json:"notes,omitempty"`
	HasTrack      bool       `json:"has_track"`
	TrackPointCnt int64      `json:"track_point_count"`
}

type TrackPoint struct {
	RecordedAt time.Time `json:"recorded_at"`
	Lat        float64   `json:"lat"`
	Lon        float64   `json:"lon"`
	Elevation  *float64  `json:"elevation_m,omitempty"`
	Accuracy   *float64  `json:"accuracy_m,omitempty"`
	Speed      *float64  `json:"speed_mps,omitempty"`
}

func (s *Server) Routes(mux *http.ServeMux) {
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	mux.HandleFunc("/api/users", s.handleUsers)
	mux.HandleFunc("/api/types", s.handleTypes)
	mux.HandleFunc("/api/activities", s.handleActivities)
	mux.HandleFunc("/api/activities/", s.handleActivityByID)
	mux.HandleFunc("/api/overall-pace", s.handleOverallPace)
}

// --- helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// --- users ---

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		rows, err := s.DB.Query(`SELECT id, name, created_at FROM users ORDER BY name`)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		defer rows.Close()
		out := []User{}
		for rows.Next() {
			var u User
			if err := rows.Scan(&u.ID, &u.Name, &u.CreatedAt); err != nil {
				writeErr(w, 500, err.Error())
				return
			}
			out = append(out, u)
		}
		writeJSON(w, 200, out)
	case http.MethodPost:
		var in struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Name) == "" {
			writeErr(w, 400, "name required")
			return
		}
		res, err := s.DB.Exec(`INSERT INTO users (name) VALUES (?)`, strings.TrimSpace(in.Name))
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		id, _ := res.LastInsertId()
		writeJSON(w, 201, User{ID: id, Name: in.Name})
	default:
		writeErr(w, 405, "method not allowed")
	}
}

// --- types ---

func (s *Server) handleTypes(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		includeRetired := r.URL.Query().Get("all") == "1"
		q := `SELECT id, name, retired FROM activity_types`
		if !includeRetired {
			q += ` WHERE retired = 0`
		}
		q += ` ORDER BY name`
		rows, err := s.DB.Query(q)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		defer rows.Close()
		out := []ActivityType{}
		for rows.Next() {
			var t ActivityType
			var retired int
			if err := rows.Scan(&t.ID, &t.Name, &retired); err != nil {
				writeErr(w, 500, err.Error())
				return
			}
			t.Retired = retired == 1
			out = append(out, t)
		}
		writeJSON(w, 200, out)
	case http.MethodPost:
		var in struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Name) == "" {
			writeErr(w, 400, "name required")
			return
		}
		res, err := s.DB.Exec(`INSERT INTO activity_types (name) VALUES (?)`, strings.TrimSpace(in.Name))
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		id, _ := res.LastInsertId()
		writeJSON(w, 201, ActivityType{ID: id, Name: in.Name})
	default:
		writeErr(w, 405, "method not allowed")
	}
}

// --- activities ---

func (s *Server) handleActivities(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.listActivities(w, r)
	case http.MethodPost:
		s.createActivity(w, r)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) listActivities(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	where := []string{"1=1"}
	args := []any{}

	if v := q.Get("user_id"); v != "" {
		where = append(where, "a.user_id = ?")
		args = append(args, v)
	}
	if v := q.Get("type_id"); v != "" {
		where = append(where, "a.type_id = ?")
		args = append(args, v)
	}
	if v := q.Get("type"); v != "" {
		where = append(where, "t.name = ?")
		args = append(args, v)
	}
	if v := q.Get("from"); v != "" {
		where = append(where, "a.activity_date >= ?")
		args = append(args, v)
	}
	if v := q.Get("to"); v != "" {
		where = append(where, "a.activity_date <= ?")
		args = append(args, v)
	}

	sqlStr := fmt.Sprintf(`
		SELECT a.id, a.user_id, a.type_id, t.name, a.name, a.activity_date,
		       a.started_at, a.stopped_at, a.total_seconds, COALESCE(a.notes,''),
		       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id) AS pts
		FROM activities a
		JOIN activity_types t ON t.id = a.type_id
		WHERE %s
		ORDER BY a.activity_date DESC, a.id DESC`, strings.Join(where, " AND "))

	rows, err := s.DB.Query(sqlStr, args...)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := []Activity{}
	for rows.Next() {
		var a Activity
		var started, stopped sql.NullTime
		var total sql.NullInt64
		if err := rows.Scan(&a.ID, &a.UserID, &a.TypeID, &a.TypeName, &a.Name, &a.ActivityDate,
			&started, &stopped, &total, &a.Notes, &a.TrackPointCnt); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		if started.Valid {
			a.StartedAt = &started.Time
		}
		if stopped.Valid {
			a.StoppedAt = &stopped.Time
		}
		if total.Valid {
			a.TotalSeconds = &total.Int64
		}
		a.HasTrack = a.TrackPointCnt > 0
		out = append(out, a)
	}
	writeJSON(w, 200, out)
}

type activityInput struct {
	UserID       int64        `json:"user_id"`
	TypeID       int64        `json:"type_id"`
	Name         string       `json:"name"`
	ActivityDate string       `json:"activity_date"`
	StartedAt    *time.Time   `json:"started_at,omitempty"`
	StoppedAt    *time.Time   `json:"stopped_at,omitempty"`
	TotalSeconds *int64       `json:"total_seconds,omitempty"`
	Notes        string       `json:"notes,omitempty"`
	Track        []TrackPoint `json:"track,omitempty"`
}

func (s *Server) createActivity(w http.ResponseWriter, r *http.Request) {
	var in activityInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	if in.UserID == 0 || in.TypeID == 0 || in.Name == "" || in.ActivityDate == "" {
		writeErr(w, 400, "user_id, type_id, name, activity_date required")
		return
	}
	// Derive total_seconds from start/stop if not supplied.
	if in.TotalSeconds == nil && in.StartedAt != nil && in.StoppedAt != nil {
		d := int64(in.StoppedAt.Sub(*in.StartedAt).Seconds())
		in.TotalSeconds = &d
	}

	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()

	res, err := tx.Exec(`INSERT INTO activities
		(user_id, type_id, name, activity_date, started_at, stopped_at, total_seconds, notes)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		in.UserID, in.TypeID, in.Name, in.ActivityDate,
		nullTime(in.StartedAt), nullTime(in.StoppedAt), nullInt(in.TotalSeconds), in.Notes)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	id, _ := res.LastInsertId()

	if len(in.Track) > 0 {
		stmt, err := tx.Prepare(`INSERT INTO track_points
			(activity_id, recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps)
			VALUES (?, ?, ?, ?, ?, ?, ?)`)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		defer stmt.Close()
		for _, p := range in.Track {
			if _, err := stmt.Exec(id, p.RecordedAt, p.Lat, p.Lon,
				nullFloat(p.Elevation), nullFloat(p.Accuracy), nullFloat(p.Speed)); err != nil {
				writeErr(w, 400, err.Error())
				return
			}
		}
	}

	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 201, map[string]int64{"id": id})
}

func (s *Server) handleActivityByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/activities/")
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeErr(w, 400, "bad activity id")
		return
	}
	// /api/activities/{id}/track
	if len(parts) == 2 && parts[1] == "track" {
		s.getTrack(w, r, id)
		return
	}
	// /api/activities/{id}/detail
	if len(parts) == 2 && parts[1] == "detail" {
		s.getActivityDetail(w, r, id)
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.getActivity(w, r, id)
	case http.MethodDelete:
		if _, err := s.DB.Exec(`DELETE FROM activities WHERE id = ?`, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getActivity(w http.ResponseWriter, r *http.Request, id int64) {
	row := s.DB.QueryRow(`
		SELECT a.id, a.user_id, a.type_id, t.name, a.name, a.activity_date,
		       a.started_at, a.stopped_at, a.total_seconds, COALESCE(a.notes,''),
		       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id)
		FROM activities a JOIN activity_types t ON t.id = a.type_id
		WHERE a.id = ?`, id)
	var a Activity
	var started, stopped sql.NullTime
	var total sql.NullInt64
	err := row.Scan(&a.ID, &a.UserID, &a.TypeID, &a.TypeName, &a.Name, &a.ActivityDate,
		&started, &stopped, &total, &a.Notes, &a.TrackPointCnt)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if started.Valid {
		a.StartedAt = &started.Time
	}
	if stopped.Valid {
		a.StoppedAt = &stopped.Time
	}
	if total.Valid {
		a.TotalSeconds = &total.Int64
	}
	a.HasTrack = a.TrackPointCnt > 0
	writeJSON(w, 200, a)
}

func (s *Server) getTrack(w http.ResponseWriter, r *http.Request, id int64) {
	rows, err := s.DB.Query(`
		SELECT recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps
		FROM track_points WHERE activity_id = ? ORDER BY recorded_at`, id)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := []TrackPoint{}
	for rows.Next() {
		var p TrackPoint
		var ele, acc, spd sql.NullFloat64
		if err := rows.Scan(&p.RecordedAt, &p.Lat, &p.Lon, &ele, &acc, &spd); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		if ele.Valid {
			v := ele.Float64
			p.Elevation = &v
		}
		if acc.Valid {
			v := acc.Float64
			p.Accuracy = &v
		}
		if spd.Valid {
			v := spd.Float64
			p.Speed = &v
		}
		out = append(out, p)
	}
	writeJSON(w, 200, out)
}

type activityDetail struct {
	Activity        Activity     `json:"activity"`
	Track           []TrackPoint `json:"track"`
	Splits          []Split      `json:"splits"`
	TotalMeters     float64      `json:"total_meters"`
	ThisAvgSecsPerKm float64     `json:"this_avg_secs_per_km"`
	OverallAvgSecsPerKm float64  `json:"overall_avg_secs_per_km"`
}

func (s *Server) getActivityDetail(w http.ResponseWriter, r *http.Request, id int64) {
	row := s.DB.QueryRow(`
		SELECT a.id, a.user_id, a.type_id, t.name, a.name, a.activity_date,
		       a.started_at, a.stopped_at, a.total_seconds, COALESCE(a.notes,''),
		       (SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id)
		FROM activities a JOIN activity_types t ON t.id = a.type_id
		WHERE a.id = ?`, id)
	var a Activity
	var started, stopped sql.NullTime
	var total sql.NullInt64
	err := row.Scan(&a.ID, &a.UserID, &a.TypeID, &a.TypeName, &a.Name, &a.ActivityDate,
		&started, &stopped, &total, &a.Notes, &a.TrackPointCnt)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if started.Valid {
		a.StartedAt = &started.Time
	}
	if stopped.Valid {
		a.StoppedAt = &stopped.Time
	}
	if total.Valid {
		a.TotalSeconds = &total.Int64
	}
	a.HasTrack = a.TrackPointCnt > 0

	// Load track points.
	rows, err := s.DB.Query(`
		SELECT recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps
		FROM track_points WHERE activity_id = ? ORDER BY recorded_at`, id)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	points := []TrackPoint{}
	for rows.Next() {
		var p TrackPoint
		var ele, acc, spd sql.NullFloat64
		if err := rows.Scan(&p.RecordedAt, &p.Lat, &p.Lon, &ele, &acc, &spd); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		if ele.Valid { v := ele.Float64; p.Elevation = &v }
		if acc.Valid { v := acc.Float64; p.Accuracy = &v }
		if spd.Valid { v := spd.Float64; p.Speed = &v }
		points = append(points, p)
	}

	splits, meters := computeSplits(points)
	detail := activityDetail{Activity: a, Track: points, Splits: splits, TotalMeters: meters}
	if len(splits) > 0 {
		var sum float64
		for _, sp := range splits {
			sum += sp.Seconds
		}
		detail.ThisAvgSecsPerKm = sum / float64(len(splits))
	}

	// Overall avg: iterate sibling activities (same user+type), accumulate splits.
	// Fast enough for a self-hosted install.
	sibs, err := s.DB.Query(`
		SELECT id FROM activities WHERE user_id = ? AND type_id = ? AND id != ?`,
		a.UserID, a.TypeID, a.ID)
	if err == nil {
		var allSecs, allKms float64
		var ids []int64
		for sibs.Next() {
			var sid int64
			if err := sibs.Scan(&sid); err == nil {
				ids = append(ids, sid)
			}
		}
		sibs.Close()
		for _, sid := range ids {
			sp, _ := loadSplits(s.DB, sid)
			for _, x := range sp {
				allSecs += x.Seconds
				allKms++
			}
		}
		if allKms > 0 {
			detail.OverallAvgSecsPerKm = allSecs / allKms
		}
	}

	writeJSON(w, 200, detail)
}

func loadSplits(db *sql.DB, activityID int64) ([]Split, float64) {
	rows, err := db.Query(`
		SELECT recorded_at, lat, lon, elevation_m, accuracy_m, speed_mps
		FROM track_points WHERE activity_id = ? ORDER BY recorded_at`, activityID)
	if err != nil {
		return nil, 0
	}
	defer rows.Close()
	points := []TrackPoint{}
	for rows.Next() {
		var p TrackPoint
		var ele, acc, spd sql.NullFloat64
		if err := rows.Scan(&p.RecordedAt, &p.Lat, &p.Lon, &ele, &acc, &spd); err == nil {
			points = append(points, p)
		}
	}
	return computeSplits(points)
}

func (s *Server) handleOverallPace(w http.ResponseWriter, r *http.Request) {
	// Returns avg secs/km per (user_id, type_id). Not used by current UI
	// — detail endpoint already supplies the comparison — but handy to have.
	writeJSON(w, 200, map[string]string{"info": "use /api/activities/{id}/detail"})
}

func nullTime(t *time.Time) any {
	if t == nil {
		return nil
	}
	return *t
}
func nullInt(i *int64) any {
	if i == nil {
		return nil
	}
	return *i
}
func nullFloat(f *float64) any {
	if f == nil {
		return nil
	}
	return *f
}
