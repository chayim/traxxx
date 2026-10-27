package main

import (
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"html/template"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed templates/*.html templates/fragments/*.html
var tmplFS embed.FS

var uiTmpl = template.Must(template.New("").Funcs(template.FuncMap{
	"intOrDash": func(p *int) string {
		if p == nil {
			return "—"
		}
		return strconv.Itoa(*p)
	},
	"intOrEmpty": func(p *int) string {
		if p == nil {
			return ""
		}
		return strconv.Itoa(*p)
	},
	"add": func(a, b int) int { return a + b },
	"hms": func(secs int64) string {
		if secs <= 0 {
			return "—"
		}
		h := secs / 3600
		m := (secs % 3600) / 60
		if h > 0 {
			return fmt.Sprintf("%d:%02d", h, m)
		}
		s := secs % 60
		return fmt.Sprintf("0:%02d:%02d", m, s)
	},
	"dateOnly": func(s string) string {
		if len(s) >= 10 {
			return s[:10]
		}
		return s
	},
	"dict": func(kv ...any) map[string]any {
		m := make(map[string]any, len(kv)/2)
		for i := 0; i+1 < len(kv); i += 2 {
			k, _ := kv[i].(string)
			m[k] = kv[i+1]
		}
		return m
	},
}).ParseFS(tmplFS, "templates/*.html", "templates/fragments/*.html"))

func (s *Server) registerUIRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/", s.handleIndex)
	mux.HandleFunc("/ui/activities", s.uiActivities)
	mux.HandleFunc("/ui/activities/", s.uiActivityDetail)
	mux.HandleFunc("/ui/settings", s.uiSettings)
	mux.HandleFunc("/ui/workouts", s.uiWorkouts)
	mux.HandleFunc("/ui/workouts/", s.uiWorkoutByID)
	mux.HandleFunc("/ui/templates/workouts/", s.uiTemplateByID)
	mux.HandleFunc("/ui/templates/workouts/delete", s.uiDeleteTemplate)
	mux.HandleFunc("/ui/fragments/exercise-row", s.fragExerciseRow)
	mux.HandleFunc("/ui/fragments/set-row", s.fragSetRow)
	mux.HandleFunc("/ui/workouts/clone", s.uiCloneTemplate)
	mux.HandleFunc("/ui/workouts/delete", s.uiDeleteWorkout)
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	http.Redirect(w, r, "/ui/activities", http.StatusFound)
}

func (s *Server) resolveUserID(r *http.Request) (int64, error) {
	if v := r.URL.Query().Get("user_id"); v != "" {
		return strconv.ParseInt(v, 10, 64)
	}
	// Fallback: first user alphabetically (seed inserts "demo").
	var id int64
	err := s.DB.QueryRow(`SELECT id FROM users ORDER BY id LIMIT 1`).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return id, err
}

type uiShell struct {
	ActiveTab string
	UserID    int64
	Title     string
}

func (s *Server) uiActivities(w http.ResponseWriter, r *http.Request) {
	userID, err := s.resolveUserID(r)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	rows, err := s.DB.Query(`SELECT a.id, a.name, a.activity_date, t.name,
		a.total_seconds,
		(SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id)
		FROM activities a JOIN activity_types t ON t.id = a.type_id
		WHERE a.user_id = ? ORDER BY a.activity_date DESC, a.id DESC LIMIT 200`, userID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	type row struct {
		ID         int64
		Name       string
		Date       string
		TypeName   string
		TotalSecs  int64
		HasTrack   bool
		PointCount int64
	}
	var out []row
	for rows.Next() {
		var r row
		var total sql.NullInt64
		if err := rows.Scan(&r.ID, &r.Name, &r.Date, &r.TypeName, &total, &r.PointCount); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		if total.Valid {
			r.TotalSecs = total.Int64
		}
		r.HasTrack = r.PointCount > 0
		out = append(out, r)
	}
	render(w, "activities.html", struct {
		Shell      uiShell
		Activities []row
	}{uiShell{ActiveTab: "activities", UserID: userID, Title: "Activities"}, out})
}

func (s *Server) uiActivityDetail(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/ui/activities/")
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	var a struct {
		ID        int64
		UserID    int64
		Name      string
		Date      string
		TypeName  string
		Notes     string
		TotalSecs int64
		HasTrack  bool
	}
	var total sql.NullInt64
	var ptCount int64
	err = s.DB.QueryRow(`SELECT a.id, a.user_id, a.name, a.activity_date, t.name,
		COALESCE(a.notes,''), a.total_seconds,
		(SELECT COUNT(*) FROM track_points p WHERE p.activity_id = a.id)
		FROM activities a JOIN activity_types t ON t.id = a.type_id
		WHERE a.id = ?`, id).
		Scan(&a.ID, &a.UserID, &a.Name, &a.Date, &a.TypeName, &a.Notes, &total, &ptCount)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	if total.Valid {
		a.TotalSecs = total.Int64
	}
	a.HasTrack = ptCount > 0
	render(w, "activity_detail.html", struct {
		Shell    uiShell
		Activity any
	}{uiShell{ActiveTab: "activities", UserID: a.UserID, Title: a.Name}, a})
}

func (s *Server) uiWorkouts(w http.ResponseWriter, r *http.Request) {
	userID, err := s.resolveUserID(r)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	wkRows, err := s.DB.Query(`SELECT w.id, w.name, w.workout_date,
		(SELECT COUNT(*) FROM workout_exercises e WHERE e.workout_id = w.id)
		FROM workouts w
		WHERE w.user_id = ? ORDER BY w.workout_date DESC, w.id DESC`, userID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer wkRows.Close()
	type wkRow struct {
		ID      int64
		Name    string
		Date    string
		ExCount int
	}
	var workouts []wkRow
	for wkRows.Next() {
		var r wkRow
		if err := wkRows.Scan(&r.ID, &r.Name, &r.Date, &r.ExCount); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		workouts = append(workouts, r)
	}
	render(w, "workouts_list.html", struct {
		Shell    uiShell
		Workouts []wkRow
	}{uiShell{ActiveTab: "workouts", UserID: userID, Title: "Workouts"}, workouts})
}

func (s *Server) uiSettings(w http.ResponseWriter, r *http.Request) {
	userID, err := s.resolveUserID(r)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	tplRows, err := s.DB.Query(`SELECT t.id, t.name,
		(SELECT COUNT(*) FROM template_exercises e WHERE e.template_id = t.id)
		FROM workout_templates t WHERE t.user_id = ? ORDER BY t.name`, userID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer tplRows.Close()
	type tplRow struct {
		ID      int64
		Name    string
		ExCount int
	}
	var templates []tplRow
	for tplRows.Next() {
		var r tplRow
		if err := tplRows.Scan(&r.ID, &r.Name, &r.ExCount); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		templates = append(templates, r)
	}
	render(w, "settings.html", struct {
		Shell     uiShell
		Templates []tplRow
	}{uiShell{ActiveTab: "settings", UserID: userID, Title: "Settings"}, templates})
}

func (s *Server) uiWorkoutByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/ui/workouts/")
	if rest == "" || rest == "clone" || rest == "delete" {
		http.NotFound(w, r)
		return
	}
	// POST to save
	if r.Method == http.MethodPost {
		s.uiSaveWorkout(w, r)
		return
	}
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	wk, err := s.loadWorkout(id)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	render(w, "workout_edit.html", struct {
		Shell   uiShell
		Workout Workout
	}{uiShell{ActiveTab: "workouts", UserID: wk.UserID, Title: wk.Name}, wk})
}

func (s *Server) uiTemplateByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/ui/templates/workouts/")
	if rest == "" || rest == "delete" {
		http.NotFound(w, r)
		return
	}
	if r.Method == http.MethodPost {
		s.uiSaveTemplate(w, r)
		return
	}
	if rest == "new" {
		userID, _ := s.resolveUserID(r)
		tpl := WorkoutTemplate{UserID: userID, Name: "", Exercises: []TemplateExercise{}}
		render(w, "template_edit.html", struct {
			Shell    uiShell
			Template WorkoutTemplate
			IsNew    bool
		}{uiShell{ActiveTab: "workouts", UserID: userID, Title: "New template"}, tpl, true})
		return
	}
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	tpl, err := s.loadWorkoutTemplate(id)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	render(w, "template_edit.html", struct {
		Shell    uiShell
		Template WorkoutTemplate
		IsNew    bool
	}{uiShell{ActiveTab: "workouts", UserID: tpl.UserID, Title: tpl.Name}, tpl, false})
}

func (s *Server) uiSaveTemplate(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/ui/templates/workouts/")
	userID, _ := strconv.ParseInt(r.FormValue("user_id"), 10, 64)
	name := strings.TrimSpace(r.FormValue("name"))
	exs := parseExerciseForm(r.PostForm)

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer tx.Rollback()

	var id int64
	if rest == "new" {
		if userID == 0 || name == "" {
			http.Error(w, "user_id and name required", 400)
			return
		}
		res, err := tx.Exec(`INSERT INTO workout_templates (user_id, name) VALUES (?, ?)`, userID, name)
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		id, _ = res.LastInsertId()
	} else {
		id, err = strconv.ParseInt(rest, 10, 64)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		if _, err := tx.Exec(`UPDATE workout_templates SET name = ? WHERE id = ?`, name, id); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		if _, err := tx.Exec(`DELETE FROM template_exercises WHERE template_id = ?`, id); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
	}
	if err := insertTemplateExercises(tx, id, exs); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/settings", http.StatusSeeOther)
}

func (s *Server) uiSaveWorkout(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/ui/workouts/")
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	name := strings.TrimSpace(r.FormValue("name"))
	date := strings.TrimSpace(r.FormValue("workout_date"))
	notes := r.FormValue("notes")
	exs := parseWorkoutExerciseForm(r.PostForm)

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`UPDATE workouts SET name = ?, workout_date = ?, notes = ? WHERE id = ?`,
		name, date, notes, id); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if _, err := tx.Exec(`DELETE FROM workout_exercises WHERE workout_id = ?`, id); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	if err := insertWorkoutExercises(tx, id, exs); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/workouts", http.StatusSeeOther)
}

func (s *Server) uiCloneTemplate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	tplID, err := strconv.ParseInt(r.FormValue("template_id"), 10, 64)
	if err != nil {
		http.Error(w, "bad template_id", 400)
		return
	}
	date := strings.TrimSpace(r.FormValue("workout_date"))
	if date == "" {
		date = time.Now().Format("2006-01-02")
	}
	tpl, err := s.loadWorkoutTemplate(tplID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	newID, err := s.cloneTemplateInto(tpl, tpl.Name, date)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/workouts/"+strconv.FormatInt(newID, 10), http.StatusSeeOther)
}

func (s *Server) uiDeleteWorkout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "bad id", 400)
		return
	}
	if _, err := s.DB.Exec(`DELETE FROM workouts WHERE id = ?`, id); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/workouts", http.StatusSeeOther)
}

func (s *Server) uiDeleteTemplate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "bad id", 400)
		return
	}
	if _, err := s.DB.Exec(`DELETE FROM workout_templates WHERE id = ?`, id); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/settings", http.StatusSeeOther)
}

// --- HTMX fragments ---

func (s *Server) fragExerciseRow(w http.ResponseWriter, r *http.Request) {
	idx, _ := strconv.Atoi(r.URL.Query().Get("idx"))
	withSets := r.URL.Query().Get("sets") == "1"
	data := struct {
		Idx      int
		WithSets bool
		Exercise WorkoutExercise
	}{Idx: idx, WithSets: withSets, Exercise: WorkoutExercise{Sets: []WorkoutSet{}}}
	tmpl := "exercise_row.html"
	if withSets {
		tmpl = "workout_exercise_row.html"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := uiTmpl.ExecuteTemplate(w, tmpl, data); err != nil {
		http.Error(w, err.Error(), 500)
	}
}

func (s *Server) fragSetRow(w http.ResponseWriter, r *http.Request) {
	exIdx, _ := strconv.Atoi(r.URL.Query().Get("ex_idx"))
	setIdx, _ := strconv.Atoi(r.URL.Query().Get("set_idx"))
	data := struct {
		ExIdx  int
		SetIdx int
		Set    WorkoutSet
	}{ExIdx: exIdx, SetIdx: setIdx, Set: WorkoutSet{SetNumber: setIdx + 1}}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := uiTmpl.ExecuteTemplate(w, "set_row.html", data); err != nil {
		http.Error(w, err.Error(), 500)
	}
}

// --- form parsing ---
//
// Exercise and set rows post their data as parallel arrays:
//   ex[].name, ex[].sets, ex[].reps, ex[].seconds (for templates)
//   ex[].name, ex[].sets, ex[].reps, ex[].seconds plus set[I][].reps/seconds (for workouts)
// Indices are the client-side DOM order; we re-position them sequentially server-side.

func parseExerciseForm(form map[string][]string) []TemplateExercise {
	names := form["ex_name"]
	setsArr := form["ex_sets"]
	repsArr := form["ex_reps"]
	secsArr := form["ex_seconds"]
	out := make([]TemplateExercise, 0, len(names))
	for i, n := range names {
		name := strings.TrimSpace(n)
		if name == "" {
			continue
		}
		e := TemplateExercise{Position: len(out) + 1, Name: name}
		e.TargetSets = parseIntPtr(get(setsArr, i))
		e.TargetReps = parseIntPtr(get(repsArr, i))
		e.TargetSeconds = parseIntPtr(get(secsArr, i))
		out = append(out, e)
	}
	return out
}

func parseWorkoutExerciseForm(form map[string][]string) []WorkoutExercise {
	names := form["ex_name"]
	setsArr := form["ex_sets"]
	repsArr := form["ex_reps"]
	secsArr := form["ex_seconds"]
	exIdxArr := form["ex_idx"] // original DOM idx used to key set inputs
	out := make([]WorkoutExercise, 0, len(names))
	for i, n := range names {
		name := strings.TrimSpace(n)
		if name == "" {
			continue
		}
		e := WorkoutExercise{Position: len(out) + 1, Name: name, Sets: []WorkoutSet{}}
		e.TargetSets = parseIntPtr(get(setsArr, i))
		e.TargetReps = parseIntPtr(get(repsArr, i))
		e.TargetSeconds = parseIntPtr(get(secsArr, i))
		idxKey := get(exIdxArr, i)
		if idxKey == "" {
			idxKey = strconv.Itoa(i)
		}
		setReps := form["set_"+idxKey+"_reps"]
		setSecs := form["set_"+idxKey+"_seconds"]
		n := len(setReps)
		if len(setSecs) > n {
			n = len(setSecs)
		}
		for j := 0; j < n; j++ {
			st := WorkoutSet{SetNumber: j + 1}
			st.Reps = parseIntPtr(get(setReps, j))
			st.Seconds = parseIntPtr(get(setSecs, j))
			if st.Reps == nil && st.Seconds == nil {
				continue
			}
			e.Sets = append(e.Sets, st)
		}
		out = append(out, e)
	}
	return out
}

func get(arr []string, i int) string {
	if i < 0 || i >= len(arr) {
		return ""
	}
	return arr[i]
}

func parseIntPtr(s string) *int {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return nil
	}
	return &v
}

func render(w http.ResponseWriter, name string, data any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := uiTmpl.ExecuteTemplate(w, name, data); err != nil {
		http.Error(w, err.Error(), 500)
	}
}
