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

type TemplateExercise struct {
	ID            int64  `json:"id"`
	TemplateID    int64  `json:"template_id"`
	Position      int    `json:"position"`
	Name          string `json:"name"`
	TargetSets    *int   `json:"target_sets,omitempty"`
	TargetReps    *int   `json:"target_reps,omitempty"`
	TargetSeconds *int   `json:"target_seconds,omitempty"`
}

type WorkoutTemplate struct {
	ID        int64              `json:"id"`
	UserID    int64              `json:"user_id"`
	Name      string             `json:"name"`
	CreatedAt time.Time          `json:"created_at"`
	Exercises []TemplateExercise `json:"exercises"`
}

type WorkoutSet struct {
	ID         int64 `json:"id"`
	ExerciseID int64 `json:"exercise_id"`
	SetNumber  int   `json:"set_number"`
	Reps       *int  `json:"reps,omitempty"`
	Seconds    *int  `json:"seconds,omitempty"`
}

type WorkoutExercise struct {
	ID            int64        `json:"id"`
	WorkoutID     int64        `json:"workout_id"`
	Position      int          `json:"position"`
	Name          string       `json:"name"`
	TargetSets    *int         `json:"target_sets,omitempty"`
	TargetReps    *int         `json:"target_reps,omitempty"`
	TargetSeconds *int         `json:"target_seconds,omitempty"`
	Sets          []WorkoutSet `json:"sets"`
}

type Workout struct {
	ID          int64             `json:"id"`
	UserID      int64             `json:"user_id"`
	TemplateID  *int64            `json:"template_id,omitempty"`
	Name        string            `json:"name"`
	WorkoutDate string            `json:"workout_date"`
	Notes       string            `json:"notes,omitempty"`
	CreatedAt   time.Time         `json:"created_at"`
	Exercises   []WorkoutExercise `json:"exercises"`
}

// --- template handlers ---

func (s *Server) handleWorkoutTemplates(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.listWorkoutTemplates(w, r)
	case http.MethodPost:
		s.createWorkoutTemplate(w, r)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) listWorkoutTemplates(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rows, err := s.Q.ListWorkoutTemplates(ctx, parseNullInt64(r.URL.Query().Get("user_id")))
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	out := make([]WorkoutTemplate, 0, len(rows))
	for _, t := range rows {
		exs, err := s.loadTemplateExercises(ctx, t.ID)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out = append(out, WorkoutTemplate{
			ID: t.ID, UserID: t.UserID, Name: t.Name, CreatedAt: t.CreatedAt,
			Exercises: exs,
		})
	}
	writeJSON(w, 200, out)
}

func (s *Server) loadTemplateExercises(ctx context.Context, templateID int64) ([]TemplateExercise, error) {
	rows, err := s.Q.ListTemplateExercises(ctx, templateID)
	if err != nil {
		return nil, err
	}
	out := make([]TemplateExercise, 0, len(rows))
	for _, e := range rows {
		out = append(out, TemplateExercise{
			ID: e.ID, TemplateID: e.TemplateID, Position: int(e.Position), Name: e.Name,
			TargetSets: nullInt64ToIntPtr(e.TargetSets), TargetReps: nullInt64ToIntPtr(e.TargetReps),
			TargetSeconds: nullInt64ToIntPtr(e.TargetSeconds),
		})
	}
	return out, nil
}

type templateInput struct {
	UserID    int64              `json:"user_id"`
	Name      string             `json:"name"`
	Exercises []TemplateExercise `json:"exercises"`
}

func (s *Server) createWorkoutTemplate(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in templateInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	if in.UserID == 0 || strings.TrimSpace(in.Name) == "" {
		writeErr(w, 400, "user_id and name required")
		return
	}
	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)
	id, err := qTx.CreateWorkoutTemplate(ctx, dbstore.CreateWorkoutTemplateParams{
		UserID: in.UserID, Name: strings.TrimSpace(in.Name),
	})
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := insertTemplateExercises(ctx, qTx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutTemplate(w, r, id)
}

func insertTemplateExercises(ctx context.Context, q *dbstore.Queries, templateID int64, exs []TemplateExercise) error {
	pos := 0
	for i, e := range exs {
		name := strings.TrimSpace(e.Name)
		if name == "" {
			continue
		}
		p := e.Position
		if p == 0 {
			p = i + 1
		}
		pos = p
		if err := q.CreateTemplateExercise(ctx, dbstore.CreateTemplateExerciseParams{
			TemplateID:    templateID,
			Position:      int64(pos),
			Name:          name,
			TargetSets:    intPtrToNullInt64(e.TargetSets),
			TargetReps:    intPtrToNullInt64(e.TargetReps),
			TargetSeconds: intPtrToNullInt64(e.TargetSeconds),
		}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) handleWorkoutTemplateByID(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rest := strings.TrimPrefix(r.URL.Path, "/api/templates/workouts/")
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeErr(w, 400, "bad template id")
		return
	}
	if len(parts) == 2 && parts[1] == "clone" && r.Method == http.MethodPost {
		s.cloneWorkoutTemplate(w, r, id)
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.getWorkoutTemplate(w, r, id)
	case http.MethodPut:
		s.updateWorkoutTemplate(w, r, id)
	case http.MethodDelete:
		if err := s.Q.DeleteWorkoutTemplate(ctx, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getWorkoutTemplate(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	t, err := s.loadWorkoutTemplate(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, t)
}

func (s *Server) loadWorkoutTemplate(ctx context.Context, id int64) (WorkoutTemplate, error) {
	t, err := s.Q.GetWorkoutTemplate(ctx, id)
	if err != nil {
		return WorkoutTemplate{}, err
	}
	exs, err := s.loadTemplateExercises(ctx, id)
	if err != nil {
		return WorkoutTemplate{}, err
	}
	return WorkoutTemplate{
		ID: t.ID, UserID: t.UserID, Name: t.Name, CreatedAt: t.CreatedAt,
		Exercises: exs,
	}, nil
}

func (s *Server) updateWorkoutTemplate(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	var in templateInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)
	if strings.TrimSpace(in.Name) != "" {
		if err := qTx.UpdateWorkoutTemplate(ctx, dbstore.UpdateWorkoutTemplateParams{
			Name: strings.TrimSpace(in.Name), ID: id,
		}); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if err := qTx.DeleteTemplateExercisesForTemplate(ctx, id); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if err := insertTemplateExercises(ctx, qTx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutTemplate(w, r, id)
}

func (s *Server) cloneWorkoutTemplate(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	var in struct {
		WorkoutDate string `json:"workout_date"`
		Name        string `json:"name"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	if in.WorkoutDate == "" {
		in.WorkoutDate = time.Now().Format("2006-01-02")
	}
	tpl, err := s.loadWorkoutTemplate(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "template not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	name := strings.TrimSpace(in.Name)
	if name == "" {
		name = tpl.Name
	}
	newID, err := s.cloneTemplateInto(ctx, tpl, name, in.WorkoutDate)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, r, newID)
}

func (s *Server) cloneTemplateInto(ctx context.Context, tpl WorkoutTemplate, name, date string) (int64, error) {
	tx, err := s.DB.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)
	wid, err := qTx.CreateWorkoutMinimal(ctx, dbstore.CreateWorkoutMinimalParams{
		UserID:      tpl.UserID,
		TemplateID:  sql.NullInt64{Int64: tpl.ID, Valid: true},
		Name:        name,
		WorkoutDate: date,
	})
	if err != nil {
		return 0, err
	}
	for _, e := range tpl.Exercises {
		if _, err := qTx.CreateWorkoutExercise(ctx, dbstore.CreateWorkoutExerciseParams{
			WorkoutID:     wid,
			Position:      int64(e.Position),
			Name:          e.Name,
			TargetSets:    intPtrToNullInt64(e.TargetSets),
			TargetReps:    intPtrToNullInt64(e.TargetReps),
			TargetSeconds: intPtrToNullInt64(e.TargetSeconds),
		}); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return wid, nil
}

// --- workout handlers ---

func (s *Server) handleWorkouts(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.listWorkouts(w, r)
	case http.MethodPost:
		s.createWorkout(w, r)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) listWorkouts(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	rows, err := s.Q.ListWorkouts(ctx, dbstore.ListWorkoutsParams{
		UserID:   parseNullInt64(q.Get("user_id")),
		DateFrom: nullStringOrEmpty(q.Get("from")),
		DateTo:   nullStringOrEmpty(q.Get("to")),
	})
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	out := make([]Workout, 0, len(rows))
	for _, wk := range rows {
		w := Workout{
			ID: wk.ID, UserID: wk.UserID, Name: wk.Name, WorkoutDate: wk.WorkoutDate,
			Notes: wk.Notes, CreatedAt: wk.CreatedAt, Exercises: []WorkoutExercise{},
		}
		if wk.TemplateID.Valid {
			v := wk.TemplateID.Int64
			w.TemplateID = &v
		}
		out = append(out, w)
	}
	writeJSON(w, 200, out)
}

type workoutInput struct {
	UserID      int64             `json:"user_id"`
	TemplateID  *int64            `json:"template_id,omitempty"`
	Name        string            `json:"name"`
	WorkoutDate string            `json:"workout_date"`
	Notes       string            `json:"notes,omitempty"`
	Exercises   []WorkoutExercise `json:"exercises"`
}

func (s *Server) createWorkout(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in workoutInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	if in.UserID == 0 || strings.TrimSpace(in.Name) == "" || in.WorkoutDate == "" {
		writeErr(w, 400, "user_id, name, workout_date required")
		return
	}
	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)
	var tplID sql.NullInt64
	if in.TemplateID != nil {
		tplID = sql.NullInt64{Int64: *in.TemplateID, Valid: true}
	}
	id, err := qTx.CreateWorkout(ctx, dbstore.CreateWorkoutParams{
		UserID: in.UserID, TemplateID: tplID, Name: strings.TrimSpace(in.Name),
		WorkoutDate: in.WorkoutDate, Notes: nullStringOrEmpty(in.Notes),
	})
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := insertWorkoutExercises(ctx, qTx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, r, id)
}

func insertWorkoutExercises(ctx context.Context, q *dbstore.Queries, workoutID int64, exs []WorkoutExercise) error {
	for i, e := range exs {
		name := strings.TrimSpace(e.Name)
		if name == "" {
			continue
		}
		pos := e.Position
		if pos == 0 {
			pos = i + 1
		}
		eid, err := q.CreateWorkoutExercise(ctx, dbstore.CreateWorkoutExerciseParams{
			WorkoutID: workoutID, Position: int64(pos), Name: name,
			TargetSets: intPtrToNullInt64(e.TargetSets),
			TargetReps: intPtrToNullInt64(e.TargetReps),
			TargetSeconds: intPtrToNullInt64(e.TargetSeconds),
		})
		if err != nil {
			return err
		}
		for j, st := range e.Sets {
			num := st.SetNumber
			if num == 0 {
				num = j + 1
			}
			if err := q.CreateWorkoutSet(ctx, dbstore.CreateWorkoutSetParams{
				ExerciseID: eid, SetNumber: int64(num),
				Reps: intPtrToNullInt64(st.Reps), Seconds: intPtrToNullInt64(st.Seconds),
			}); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *Server) handleWorkoutByID(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rest := strings.TrimPrefix(r.URL.Path, "/api/workouts/")
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeErr(w, 400, "bad workout id")
		return
	}
	if len(parts) == 2 && parts[1] == "detail" {
		s.getWorkoutDetail(w, r, id)
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.getWorkoutDetail(w, r, id)
	case http.MethodPut:
		s.updateWorkout(w, r, id)
	case http.MethodDelete:
		if err := s.Q.DeleteWorkout(ctx, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getWorkoutDetail(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	wk, err := s.loadWorkout(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, wk)
}

func (s *Server) loadWorkout(ctx context.Context, id int64) (Workout, error) {
	wk, err := s.Q.GetWorkout(ctx, id)
	if err != nil {
		return Workout{}, err
	}
	out := Workout{
		ID: wk.ID, UserID: wk.UserID, Name: wk.Name, WorkoutDate: wk.WorkoutDate,
		Notes: wk.Notes, CreatedAt: wk.CreatedAt, Exercises: []WorkoutExercise{},
	}
	if wk.TemplateID.Valid {
		v := wk.TemplateID.Int64
		out.TemplateID = &v
	}
	exRows, err := s.Q.ListWorkoutExercises(ctx, id)
	if err != nil {
		return out, err
	}
	for _, e := range exRows {
		ex := WorkoutExercise{
			ID: e.ID, WorkoutID: e.WorkoutID, Position: int(e.Position), Name: e.Name,
			TargetSets: nullInt64ToIntPtr(e.TargetSets), TargetReps: nullInt64ToIntPtr(e.TargetReps),
			TargetSeconds: nullInt64ToIntPtr(e.TargetSeconds),
			Sets:          []WorkoutSet{},
		}
		setRows, err := s.Q.ListWorkoutSetsForExercise(ctx, e.ID)
		if err != nil {
			return out, err
		}
		for _, st := range setRows {
			ex.Sets = append(ex.Sets, WorkoutSet{
				ID: st.ID, ExerciseID: st.ExerciseID, SetNumber: int(st.SetNumber),
				Reps: nullInt64ToIntPtr(st.Reps), Seconds: nullInt64ToIntPtr(st.Seconds),
			})
		}
		out.Exercises = append(out.Exercises, ex)
	}
	return out, nil
}

func (s *Server) updateWorkout(w http.ResponseWriter, r *http.Request, id int64) {
	ctx := r.Context()
	var in workoutInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	tx, err := s.DB.Begin()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer tx.Rollback()
	qTx := s.Q.WithTx(tx)
	if strings.TrimSpace(in.Name) != "" || in.WorkoutDate != "" || in.Notes != "" {
		if err := qTx.UpdateWorkoutPartial(ctx, dbstore.UpdateWorkoutPartialParams{
			Name: strings.TrimSpace(in.Name), WorkoutDate: in.WorkoutDate, Notes: nullStringOrEmpty(in.Notes), ID: id,
		}); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if err := qTx.DeleteWorkoutExercisesForWorkout(ctx, id); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if err := insertWorkoutExercises(ctx, qTx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, r, id)
}

// --- small helpers ---

func intPtrToNullInt64(p *int) sql.NullInt64 {
	if p == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: int64(*p), Valid: true}
}

func nullInt64ToIntPtr(n sql.NullInt64) *int {
	if !n.Valid {
		return nil
	}
	v := int(n.Int64)
	return &v
}
