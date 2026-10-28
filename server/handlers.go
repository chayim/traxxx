package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"traxxx/server/db/dbstore"
)

type Server struct {
	DB *sql.DB
	Q  *dbstore.Queries
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
	ID               int64      `json:"id"`
	UserID           int64      `json:"user_id"`
	TypeID           int64      `json:"type_id"`
	TypeName         string     `json:"type_name,omitempty"`
	Name             string     `json:"name"`
	ActivityDate     string     `json:"activity_date"`
	StartedAt        *time.Time `json:"started_at,omitempty"`
	StoppedAt        *time.Time `json:"stopped_at,omitempty"`
	TotalSeconds     *int64     `json:"total_seconds,omitempty"`
	Notes            string     `json:"notes,omitempty"`
	ClientUUID       string     `json:"client_uuid,omitempty"`
	HasTrack         bool       `json:"has_track"`
	TrackPointCnt    int64      `json:"track_point_count"`
	TotalMeters      *float64   `json:"total_meters,omitempty"`
	AvgSecsPerKm     *float64   `json:"avg_secs_per_km,omitempty"`
	TypeAvgSecsPerKm *float64   `json:"type_avg_secs_per_km,omitempty"`
}

type TrackPoint struct {
	RecordedAt time.Time `json:"recorded_at"`
	Lat        float64   `json:"lat"`
	Lon        float64   `json:"lon"`
	Elevation  *float64  `json:"elevation_m,omitempty"`
	Accuracy   *float64  `json:"accuracy_m,omitempty"`
	Speed      *float64  `json:"speed_mps,omitempty"`
}

func writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(body)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

// --- users ---

func (s *Server) Routes(mux *http.ServeMux) {
	mux.HandleFunc("/api/users", s.handleUsers)
	mux.HandleFunc("/api/types", s.handleTypes)
	mux.HandleFunc("/api/activities", s.handleActivities)
	mux.HandleFunc("/api/activities/", s.handleActivityByID)
	mux.HandleFunc("/api/pace", s.handleOverallPace)
	mux.HandleFunc("/api/templates/workouts", s.handleWorkoutTemplates)
	mux.HandleFunc("/api/templates/workouts/", s.handleWorkoutTemplateByID)
	mux.HandleFunc("/api/workouts", s.handleWorkouts)
	mux.HandleFunc("/api/workouts/", s.handleWorkoutByID)
	mux.HandleFunc("/api/weights", s.handleWeights)
	mux.HandleFunc("/api/weights/", s.handleWeightByID)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/download/traxxx.apk", s.handleAPKDownload)
	s.registerUIRoutes(mux)
	mux.Handle("/static/", staticHandler())
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	switch r.Method {
	case http.MethodGet:
		rows, err := s.Q.ListUsers(ctx)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out := make([]User, 0, len(rows))
		for _, u := range rows {
			out = append(out, User{ID: u.ID, Name: u.Name, CreatedAt: u.CreatedAt})
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
		id, err := s.Q.CreateUser(ctx, strings.TrimSpace(in.Name))
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		writeJSON(w, 201, User{ID: id, Name: in.Name})
	default:
		writeErr(w, 405, "method not allowed")
	}
}

// --- types ---

func (s *Server) handleTypes(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	switch r.Method {
	case http.MethodGet:
		var filter sql.NullInt64
		if r.URL.Query().Get("all") == "1" {
			filter = sql.NullInt64{Int64: 1, Valid: true}
		}
		rows, err := s.Q.ListTypes(ctx, filter)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out := make([]ActivityType, 0, len(rows))
		for _, t := range rows {
			out = append(out, ActivityType{ID: t.ID, Name: t.Name, Retired: t.Retired == 1})
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
		id, err := s.Q.CreateType(ctx, strings.TrimSpace(in.Name))
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
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
	ctx := r.Context()
	q := r.URL.Query()
	params := dbstore.ListActivitiesParams{
		UserID:   parseNullInt64(q.Get("user_id")),
		TypeID:   parseNullInt64(q.Get("type_id")),
		TypeName: nullStringOrEmpty(q.Get("type")),
		DateFrom: nullStringOrEmpty(q.Get("from")),
		DateTo:   nullStringOrEmpty(q.Get("to")),
	}
	rows, err := s.Q.ListActivities(ctx, params)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	out := make([]Activity, 0, len(rows))
	for _, r := range rows {
		a := Activity{
			ID:            r.ID,
			UserID:        r.UserID,
			TypeID:        r.TypeID,
			TypeName:      r.TypeName,
			Name:          r.Name,
			ActivityDate:  r.ActivityDate,
			Notes:         r.Notes,
			ClientUUID:    r.ClientUuid,
			TrackPointCnt: r.PointCount,
			HasTrack:      r.PointCount > 0,
		}
		if r.StartedAt.Valid {
			a.StartedAt = &r.StartedAt.Time
		}
		if r.StoppedAt.Valid {
			a.StoppedAt = &r.StoppedAt.Time
		}
		if r.TotalSeconds.Valid {
			a.TotalSeconds = &r.TotalSeconds.Int64
		}
		out = append(out, a)
	}
	s.annotatePaces(ctx, out)
	writeJSON(w, 200, out)
}

// annotatePaces fills AvgSecsPerKm / TotalMeters for every activity in `out`
// that has GPS points, and TypeAvgSecsPerKm per (user_id, type_id).
func (s *Server) annotatePaces(ctx context.Context, out []Activity) {
	if len(out) == 0 {
		return
	}
	byAct := make(map[int64][]TrackPoint, len(out))
	for i, a := range out {
		if !a.HasTrack {
			continue
		}
		rows, err := s.Q.ListTrackPoints(ctx, a.ID)
		if err != nil {
			continue
		}
		points := make([]TrackPoint, 0, len(rows))
		for _, r := range rows {
			points = append(points, trackPointFromRow(r))
		}
		byAct[a.ID] = points
		_ = i
	}

	type typeAgg struct{ secs, kms float64 }
	byType := map[int64]*typeAgg{}
	idx := make(map[int64]int, len(out))
	for i, a := range out {
		idx[a.ID] = i
	}
	for id, pts := range byAct {
		splits, meters := computeSplits(pts)
		if len(splits) == 0 {
			continue
		}
		var sumSecs float64
		for _, sp := range splits {
			sumSecs += sp.Seconds
		}
		avg := sumSecs / float64(len(splits))
		i := idx[id]
		m := meters
		out[i].TotalMeters = &m
		out[i].AvgSecsPerKm = &avg

		tid := out[i].TypeID
		agg := byType[tid]
		if agg == nil {
			agg = &typeAgg{}
			byType[tid] = agg
		}
		agg.secs += sumSecs
		agg.kms += float64(len(splits))
	}
	for i := range out {
		if agg := byType[out[i].TypeID]; agg != nil && agg.kms > 0 {
			v := agg.secs / agg.kms
			out[i].TypeAvgSecsPerKm = &v
		}
	}
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
	ClientUUID   string       `json:"client_uuid,omitempty"`
	Track        []TrackPoint `json:"track,omitempty"`
}

func (s *Server) createActivity(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in activityInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	if in.UserID == 0 || in.TypeID == 0 || in.Name == "" || in.ActivityDate == "" {
		writeErr(w, 400, "user_id, type_id, name, activity_date required")
		return
	}
	if in.TotalSeconds == nil && in.StartedAt != nil && in.StoppedAt != nil {
		d := int64(in.StoppedAt.Sub(*in.StartedAt).Seconds())
		in.TotalSeconds = &d
	}

	// Idempotency on client_uuid.
	if in.ClientUUID != "" {
		existing, err := s.Q.FindActivityByClientUUID(ctx, nullStringOrEmpty(in.ClientUUID))
		if err == nil {
			s.getActivity(w, r, existing)
			return
		}
		if !errors.Is(err, sql.ErrNoRows) {
			writeErr(w, 500, err.Error())
			return
		}
	}

	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)

	id, err := qTx.CreateActivity(ctx, dbstore.CreateActivityParams{
		UserID:       in.UserID,
		TypeID:       in.TypeID,
		Name:         in.Name,
		ActivityDate: in.ActivityDate,
		StartedAt:    nullTime(in.StartedAt),
		StoppedAt:    nullTime(in.StoppedAt),
		TotalSeconds: nullInt64Ptr(in.TotalSeconds),
		Notes:        nullStringOrEmpty(in.Notes),
		ClientUuid:   nullStringOrEmpty(in.ClientUUID),
	})
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	for _, p := range in.Track {
		if err := qTx.CreateTrackPoint(ctx, dbstore.CreateTrackPointParams{
			ActivityID:  id,
			RecordedAt:  p.RecordedAt,
			Lat:         p.Lat,
			Lon:         p.Lon,
			ElevationM:  nullFloat64Ptr(p.Elevation),
			AccuracyM:   nullFloat64Ptr(p.Accuracy),
			SpeedMps:    nullFloat64Ptr(p.Speed),
		}); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getActivity(w, r, id)
}

func (s *Server) handleActivityByID(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rest := strings.TrimPrefix(r.URL.Path, "/api/activities/")
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeErr(w, 400, "bad activity id")
		return
	}
	if len(parts) == 2 && parts[1] == "track" {
		s.getTrack(w, r, id)
		return
	}
	if len(parts) == 2 && parts[1] == "detail" {
		s.getActivityDetail(w, r, id)
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.getActivity(w, r, id)
	case http.MethodDelete:
		if err := s.Q.DeleteActivity(ctx, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getActivity(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	row, err := s.Q.GetActivity(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	a := Activity{
		ID:            row.ID,
		UserID:        row.UserID,
		TypeID:        row.TypeID,
		TypeName:      row.TypeName,
		Name:          row.Name,
		ActivityDate:  row.ActivityDate,
		Notes:         row.Notes,
		ClientUUID:    row.ClientUuid,
		TrackPointCnt: row.PointCount,
		HasTrack:      row.PointCount > 0,
	}
	if row.StartedAt.Valid {
		a.StartedAt = &row.StartedAt.Time
	}
	if row.StoppedAt.Valid {
		a.StoppedAt = &row.StoppedAt.Time
	}
	if row.TotalSeconds.Valid {
		a.TotalSeconds = &row.TotalSeconds.Int64
	}
	writeJSON(w, 200, a)
}

func (s *Server) getTrack(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	rows, err := s.Q.ListTrackPoints(ctx, id)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	out := make([]TrackPoint, 0, len(rows))
	for _, r := range rows {
		out = append(out, trackPointFromRow(r))
	}
	writeJSON(w, 200, out)
}

type activityDetail struct {
	Activity            Activity     `json:"activity"`
	Track               []TrackPoint `json:"track"`
	Splits              []Split      `json:"splits"`
	TotalMeters         float64      `json:"total_meters"`
	ThisAvgSecsPerKm    float64      `json:"this_avg_secs_per_km"`
	OverallAvgSecsPerKm float64      `json:"overall_avg_secs_per_km"`
}

func (s *Server) getActivityDetail(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	row, err := s.Q.GetActivity(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	a := Activity{
		ID: row.ID, UserID: row.UserID, TypeID: row.TypeID, TypeName: row.TypeName,
		Name: row.Name, ActivityDate: row.ActivityDate, Notes: row.Notes,
		ClientUUID: row.ClientUuid, TrackPointCnt: row.PointCount, HasTrack: row.PointCount > 0,
	}
	if row.StartedAt.Valid {
		a.StartedAt = &row.StartedAt.Time
	}
	if row.StoppedAt.Valid {
		a.StoppedAt = &row.StoppedAt.Time
	}
	if row.TotalSeconds.Valid {
		a.TotalSeconds = &row.TotalSeconds.Int64
	}

	tpRows, err := s.Q.ListTrackPoints(ctx, id)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	points := make([]TrackPoint, 0, len(tpRows))
	for _, r := range tpRows {
		points = append(points, trackPointFromRow(r))
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

	sibIDs, err := s.Q.SiblingActivityIDs(ctx, dbstore.SiblingActivityIDsParams{
		UserID: a.UserID, TypeID: a.TypeID, ExcludeID: a.ID,
	})
	if err == nil {
		var allSecs, allKms float64
		for _, sid := range sibIDs {
			sp, _ := loadSplits(ctx, s.Q, sid)
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

func loadSplits(ctx context.Context, q *dbstore.Queries, activityID int64) ([]Split, float64) {
	rows, err := q.ListTrackPoints(ctx, activityID)
	if err != nil {
		return nil, 0
	}
	points := make([]TrackPoint, 0, len(rows))
	for _, r := range rows {
		points = append(points, trackPointFromRow(r))
	}
	return computeSplits(points)
}

func (s *Server) handleOverallPace(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]string{"info": "use /api/activities/{id}/detail"})
}

// --- small helpers ---

func trackPointFromRow(r dbstore.ListTrackPointsRow) TrackPoint {
	p := TrackPoint{RecordedAt: r.RecordedAt, Lat: r.Lat, Lon: r.Lon}
	if r.ElevationM.Valid {
		v := r.ElevationM.Float64
		p.Elevation = &v
	}
	if r.AccuracyM.Valid {
		v := r.AccuracyM.Float64
		p.Accuracy = &v
	}
	if r.SpeedMps.Valid {
		v := r.SpeedMps.Float64
		p.Speed = &v
	}
	return p
}

func parseNullInt64(s string) sql.NullInt64 {
	if s == "" {
		return sql.NullInt64{}
	}
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: v, Valid: true}
}

func nullStringOrEmpty(s string) sql.NullString {
	if s == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: s, Valid: true}
}

func nullTime(t *time.Time) sql.NullTime {
	if t == nil {
		return sql.NullTime{}
	}
	return sql.NullTime{Time: *t, Valid: true}
}

func nullInt64Ptr(p *int64) sql.NullInt64 {
	if p == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: *p, Valid: true}
}

func nullFloat64Ptr(p *float64) sql.NullFloat64 {
	if p == nil {
		return sql.NullFloat64{}
	}
	return sql.NullFloat64{Float64: *p, Valid: true}
}
