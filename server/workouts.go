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
	where := []string{"1=1"}
	args := []any{}
	if v := r.URL.Query().Get("user_id"); v != "" {
		where = append(where, "user_id = ?")
		args = append(args, v)
	}
	q := fmt.Sprintf(`SELECT id, user_id, name, created_at FROM workout_templates
		WHERE %s ORDER BY name`, strings.Join(where, " AND "))
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := []WorkoutTemplate{}
	ids := []int64{}
	byID := map[int64]int{}
	for rows.Next() {
		var t WorkoutTemplate
		if err := rows.Scan(&t.ID, &t.UserID, &t.Name, &t.CreatedAt); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		t.Exercises = []TemplateExercise{}
		byID[t.ID] = len(out)
		ids = append(ids, t.ID)
		out = append(out, t)
	}
	if len(ids) > 0 {
		exs, err := s.loadTemplateExercises(ids)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		for _, e := range exs {
			i := byID[e.TemplateID]
			out[i].Exercises = append(out[i].Exercises, e)
		}
	}
	writeJSON(w, 200, out)
}

func (s *Server) loadTemplateExercises(templateIDs []int64) ([]TemplateExercise, error) {
	if len(templateIDs) == 0 {
		return nil, nil
	}
	ph := strings.Repeat("?,", len(templateIDs))
	ph = ph[:len(ph)-1]
	args := make([]any, len(templateIDs))
	for i, id := range templateIDs {
		args[i] = id
	}
	rows, err := s.DB.Query(fmt.Sprintf(`
		SELECT id, template_id, position, name, target_sets, target_reps, target_seconds
		FROM template_exercises WHERE template_id IN (%s) ORDER BY template_id, position`, ph), args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []TemplateExercise{}
	for rows.Next() {
		var e TemplateExercise
		var sets, reps, secs sql.NullInt64
		if err := rows.Scan(&e.ID, &e.TemplateID, &e.Position, &e.Name, &sets, &reps, &secs); err != nil {
			return nil, err
		}
		e.TargetSets = nullIntPtr(sets)
		e.TargetReps = nullIntPtr(reps)
		e.TargetSeconds = nullIntPtr(secs)
		out = append(out, e)
	}
	return out, nil
}

type templateInput struct {
	UserID    int64              `json:"user_id"`
	Name      string             `json:"name"`
	Exercises []TemplateExercise `json:"exercises"`
}

func (s *Server) createWorkoutTemplate(w http.ResponseWriter, r *http.Request) {
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
	res, err := tx.Exec(`INSERT INTO workout_templates (user_id, name) VALUES (?, ?)`,
		in.UserID, strings.TrimSpace(in.Name))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	id, _ := res.LastInsertId()
	if err := insertTemplateExercises(tx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutTemplate(w, id)
}

func insertTemplateExercises(tx *sql.Tx, templateID int64, exs []TemplateExercise) error {
	if len(exs) == 0 {
		return nil
	}
	stmt, err := tx.Prepare(`INSERT INTO template_exercises
		(template_id, position, name, target_sets, target_reps, target_seconds)
		VALUES (?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()
	for i, e := range exs {
		name := strings.TrimSpace(e.Name)
		if name == "" {
			continue
		}
		pos := e.Position
		if pos == 0 {
			pos = i + 1
		}
		if _, err := stmt.Exec(templateID, pos, name,
			nullIntFrom(e.TargetSets), nullIntFrom(e.TargetReps), nullIntFrom(e.TargetSeconds)); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) handleWorkoutTemplateByID(w http.ResponseWriter, r *http.Request) {
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
		s.getWorkoutTemplate(w, id)
	case http.MethodPut:
		s.updateWorkoutTemplate(w, r, id)
	case http.MethodDelete:
		if _, err := s.DB.Exec(`DELETE FROM workout_templates WHERE id = ?`, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getWorkoutTemplate(w http.ResponseWriter, id int64) {
	t, err := s.loadWorkoutTemplate(id)
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

func (s *Server) loadWorkoutTemplate(id int64) (WorkoutTemplate, error) {
	var t WorkoutTemplate
	err := s.DB.QueryRow(`SELECT id, user_id, name, created_at FROM workout_templates WHERE id = ?`, id).
		Scan(&t.ID, &t.UserID, &t.Name, &t.CreatedAt)
	if err != nil {
		return t, err
	}
	exs, err := s.loadTemplateExercises([]int64{id})
	if err != nil {
		return t, err
	}
	t.Exercises = exs
	if t.Exercises == nil {
		t.Exercises = []TemplateExercise{}
	}
	return t, nil
}

func (s *Server) updateWorkoutTemplate(w http.ResponseWriter, r *http.Request, id int64) {
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
	if strings.TrimSpace(in.Name) != "" {
		if _, err := tx.Exec(`UPDATE workout_templates SET name = ? WHERE id = ?`,
			strings.TrimSpace(in.Name), id); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if _, err := tx.Exec(`DELETE FROM template_exercises WHERE template_id = ?`, id); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if err := insertTemplateExercises(tx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutTemplate(w, id)
}

func (s *Server) cloneWorkoutTemplate(w http.ResponseWriter, r *http.Request, id int64) {
	var in struct {
		WorkoutDate string `json:"workout_date"`
		Name        string `json:"name"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	if in.WorkoutDate == "" {
		in.WorkoutDate = time.Now().Format("2006-01-02")
	}
	tpl, err := s.loadWorkoutTemplate(id)
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
	newID, err := s.cloneTemplateInto(tpl, name, in.WorkoutDate)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, newID)
}

func (s *Server) cloneTemplateInto(tpl WorkoutTemplate, name, date string) (int64, error) {
	tx, err := s.DB.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`INSERT INTO workouts (user_id, template_id, name, workout_date) VALUES (?, ?, ?, ?)`,
		tpl.UserID, tpl.ID, name, date)
	if err != nil {
		return 0, err
	}
	wid, _ := res.LastInsertId()
	stmt, err := tx.Prepare(`INSERT INTO workout_exercises
		(workout_id, position, name, target_sets, target_reps, target_seconds)
		VALUES (?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return 0, err
	}
	defer stmt.Close()
	for _, e := range tpl.Exercises {
		if _, err := stmt.Exec(wid, e.Position, e.Name,
			nullIntFrom(e.TargetSets), nullIntFrom(e.TargetReps), nullIntFrom(e.TargetSeconds)); err != nil {
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
	q := r.URL.Query()
	where := []string{"1=1"}
	args := []any{}
	if v := q.Get("user_id"); v != "" {
		where = append(where, "user_id = ?")
		args = append(args, v)
	}
	if v := q.Get("from"); v != "" {
		where = append(where, "workout_date >= ?")
		args = append(args, v)
	}
	if v := q.Get("to"); v != "" {
		where = append(where, "workout_date <= ?")
		args = append(args, v)
	}
	sqlStr := fmt.Sprintf(`SELECT id, user_id, template_id, name, workout_date,
		COALESCE(notes,''), created_at FROM workouts
		WHERE %s ORDER BY workout_date DESC, id DESC`, strings.Join(where, " AND "))
	rows, err := s.DB.Query(sqlStr, args...)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := []Workout{}
	for rows.Next() {
		var wk Workout
		var tplID sql.NullInt64
		if err := rows.Scan(&wk.ID, &wk.UserID, &tplID, &wk.Name, &wk.WorkoutDate,
			&wk.Notes, &wk.CreatedAt); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		if tplID.Valid {
			v := tplID.Int64
			wk.TemplateID = &v
		}
		wk.Exercises = []WorkoutExercise{}
		out = append(out, wk)
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
	var tplID any
	if in.TemplateID != nil {
		tplID = *in.TemplateID
	}
	res, err := tx.Exec(`INSERT INTO workouts (user_id, template_id, name, workout_date, notes)
		VALUES (?, ?, ?, ?, ?)`,
		in.UserID, tplID, strings.TrimSpace(in.Name), in.WorkoutDate, nullString(in.Notes))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	id, _ := res.LastInsertId()
	if err := insertWorkoutExercises(tx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, id)
}

func insertWorkoutExercises(tx *sql.Tx, workoutID int64, exs []WorkoutExercise) error {
	if len(exs) == 0 {
		return nil
	}
	exStmt, err := tx.Prepare(`INSERT INTO workout_exercises
		(workout_id, position, name, target_sets, target_reps, target_seconds)
		VALUES (?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer exStmt.Close()
	setStmt, err := tx.Prepare(`INSERT INTO workout_sets
		(exercise_id, set_number, reps, seconds) VALUES (?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer setStmt.Close()
	for i, e := range exs {
		name := strings.TrimSpace(e.Name)
		if name == "" {
			continue
		}
		pos := e.Position
		if pos == 0 {
			pos = i + 1
		}
		res, err := exStmt.Exec(workoutID, pos, name,
			nullIntFrom(e.TargetSets), nullIntFrom(e.TargetReps), nullIntFrom(e.TargetSeconds))
		if err != nil {
			return err
		}
		eid, _ := res.LastInsertId()
		for j, st := range e.Sets {
			num := st.SetNumber
			if num == 0 {
				num = j + 1
			}
			if _, err := setStmt.Exec(eid, num, nullIntFrom(st.Reps), nullIntFrom(st.Seconds)); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *Server) handleWorkoutByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/workouts/")
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeErr(w, 400, "bad workout id")
		return
	}
	if len(parts) == 2 && parts[1] == "detail" {
		s.getWorkoutDetail(w, id)
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.getWorkoutDetail(w, id)
	case http.MethodPut:
		s.updateWorkout(w, r, id)
	case http.MethodDelete:
		if _, err := s.DB.Exec(`DELETE FROM workouts WHERE id = ?`, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

func (s *Server) getWorkoutDetail(w http.ResponseWriter, id int64) {
	wk, err := s.loadWorkout(id)
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

func (s *Server) loadWorkout(id int64) (Workout, error) {
	var wk Workout
	var tplID sql.NullInt64
	err := s.DB.QueryRow(`SELECT id, user_id, template_id, name, workout_date,
		COALESCE(notes,''), created_at FROM workouts WHERE id = ?`, id).
		Scan(&wk.ID, &wk.UserID, &tplID, &wk.Name, &wk.WorkoutDate, &wk.Notes, &wk.CreatedAt)
	if err != nil {
		return wk, err
	}
	if tplID.Valid {
		v := tplID.Int64
		wk.TemplateID = &v
	}
	wk.Exercises = []WorkoutExercise{}

	exRows, err := s.DB.Query(`SELECT id, workout_id, position, name,
		target_sets, target_reps, target_seconds
		FROM workout_exercises WHERE workout_id = ? ORDER BY position`, id)
	if err != nil {
		return wk, err
	}
	byID := map[int64]int{}
	for exRows.Next() {
		var e WorkoutExercise
		var sets, reps, secs sql.NullInt64
		if err := exRows.Scan(&e.ID, &e.WorkoutID, &e.Position, &e.Name, &sets, &reps, &secs); err != nil {
			exRows.Close()
			return wk, err
		}
		e.TargetSets = nullIntPtr(sets)
		e.TargetReps = nullIntPtr(reps)
		e.TargetSeconds = nullIntPtr(secs)
		e.Sets = []WorkoutSet{}
		byID[e.ID] = len(wk.Exercises)
		wk.Exercises = append(wk.Exercises, e)
	}
	exRows.Close()

	if len(wk.Exercises) == 0 {
		return wk, nil
	}
	ids := make([]any, 0, len(wk.Exercises))
	for _, e := range wk.Exercises {
		ids = append(ids, e.ID)
	}
	ph := strings.Repeat("?,", len(ids))
	ph = ph[:len(ph)-1]
	setRows, err := s.DB.Query(fmt.Sprintf(`SELECT id, exercise_id, set_number, reps, seconds
		FROM workout_sets WHERE exercise_id IN (%s) ORDER BY exercise_id, set_number`, ph), ids...)
	if err != nil {
		return wk, err
	}
	defer setRows.Close()
	for setRows.Next() {
		var st WorkoutSet
		var reps, secs sql.NullInt64
		if err := setRows.Scan(&st.ID, &st.ExerciseID, &st.SetNumber, &reps, &secs); err != nil {
			return wk, err
		}
		st.Reps = nullIntPtr(reps)
		st.Seconds = nullIntPtr(secs)
		i := byID[st.ExerciseID]
		wk.Exercises[i].Sets = append(wk.Exercises[i].Sets, st)
	}
	return wk, nil
}

func (s *Server) updateWorkout(w http.ResponseWriter, r *http.Request, id int64) {
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
	if strings.TrimSpace(in.Name) != "" || in.WorkoutDate != "" || in.Notes != "" {
		if _, err := tx.Exec(`UPDATE workouts SET
			name = COALESCE(NULLIF(?, ''), name),
			workout_date = COALESCE(NULLIF(?, ''), workout_date),
			notes = ?
			WHERE id = ?`,
			strings.TrimSpace(in.Name), in.WorkoutDate, in.Notes, id); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if _, err := tx.Exec(`DELETE FROM workout_exercises WHERE workout_id = ?`, id); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if err := insertWorkoutExercises(tx, id, in.Exercises); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := tx.Commit(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	s.getWorkoutDetail(w, id)
}

// --- small helpers ---

func nullIntFrom(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}

func nullIntPtr(n sql.NullInt64) *int {
	if !n.Valid {
		return nil
	}
	v := int(n.Int64)
	return &v
}
