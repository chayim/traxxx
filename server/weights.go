package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type Weight struct {
	ID        int64   `json:"id"`
	UserID    int64   `json:"user_id"`
	WeightKg  float64 `json:"weight_kg"`
	Date      string  `json:"date"`
	CreatedAt string  `json:"created_at,omitempty"`
}

func (s *Server) handleWeights(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.listWeights(w, r)
	case http.MethodPost:
		s.createWeight(w, r)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

type weightsPageResp struct {
	Items  []Weight `json:"items"`
	Total  int64    `json:"total"`
	Limit  int      `json:"limit"`
	Offset int      `json:"offset"`
}

func (s *Server) listWeights(w http.ResponseWriter, r *http.Request) {
	uid, _ := strconv.ParseInt(r.URL.Query().Get("user_id"), 10, 64)
	if uid == 0 {
		id, err := s.Q.FirstUserID(r.Context())
		if err == nil {
			uid = id
		}
	}
	limit, offset := parsePaging(r)
	var total int64
	if err := s.DB.QueryRowContext(r.Context(),
		`SELECT COUNT(*) FROM weights WHERE user_id = $1`, uid).Scan(&total); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	q := `SELECT id, user_id, weight_kg, weight_date, created_at
		   FROM weights WHERE user_id = $1
		   ORDER BY weight_date DESC, id DESC`
	args := []any{uid}
	if limit > 0 {
		q += " LIMIT $2 OFFSET $3"
		args = append(args, limit, offset)
	}
	rows, err := s.DB.QueryContext(r.Context(), q, args...)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := []Weight{}
	for rows.Next() {
		var ww Weight
		var created time.Time
		if err := rows.Scan(&ww.ID, &ww.UserID, &ww.WeightKg, &ww.Date, &created); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		ww.CreatedAt = created.Format(time.RFC3339)
		out = append(out, ww)
	}
	writeJSON(w, 200, weightsPageResp{Items: out, Total: total, Limit: limit, Offset: offset})
}

func parsePaging(r *http.Request) (limit, offset int) {
	q := r.URL.Query()
	limit, _ = strconv.Atoi(q.Get("limit"))
	offset, _ = strconv.Atoi(q.Get("offset"))
	if limit < 0 {
		limit = 0
	}
	if offset < 0 {
		offset = 0
	}
	return limit, offset
}

func (s *Server) createWeight(w http.ResponseWriter, r *http.Request) {
	var in struct {
		UserID   int64   `json:"user_id"`
		WeightKg float64 `json:"weight_kg"`
		Date     string  `json:"date"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, 400, "invalid json")
		return
	}
	if in.UserID == 0 {
		id, _ := s.Q.FirstUserID(r.Context())
		in.UserID = id
	}
	if in.UserID == 0 || in.WeightKg <= 0 {
		writeErr(w, 400, "user_id and weight_kg required")
		return
	}
	if strings.TrimSpace(in.Date) == "" {
		in.Date = time.Now().Format("2006-01-02")
	}
	var id int64
	err := s.DB.QueryRowContext(r.Context(),
		`INSERT INTO weights (user_id, weight_kg, weight_date)
		 VALUES ($1, $2, $3) RETURNING id`,
		in.UserID, in.WeightKg, in.Date).Scan(&id)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 201, Weight{ID: id, UserID: in.UserID, WeightKg: in.WeightKg, Date: in.Date})
}

func (s *Server) handleWeightByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/weights/")
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil {
		writeErr(w, 400, "bad id")
		return
	}
	switch r.Method {
	case http.MethodDelete:
		if _, err := s.DB.ExecContext(r.Context(), `DELETE FROM weights WHERE id = $1`, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		w.WriteHeader(204)
	case http.MethodPut, http.MethodPatch:
		var in struct {
			WeightKg float64 `json:"weight_kg"`
			Date     string  `json:"date"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			writeErr(w, 400, "invalid json")
			return
		}
		if in.WeightKg <= 0 || strings.TrimSpace(in.Date) == "" {
			writeErr(w, 400, "weight_kg and date required")
			return
		}
		if _, err := s.DB.ExecContext(r.Context(),
			`UPDATE weights SET weight_kg = $1, weight_date = $2 WHERE id = $3`,
			in.WeightKg, in.Date, id); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		var out Weight
		var created time.Time
		if err := s.DB.QueryRowContext(r.Context(),
			`SELECT id, user_id, weight_kg, weight_date, created_at FROM weights WHERE id = $1`, id).
			Scan(&out.ID, &out.UserID, &out.WeightKg, &out.Date, &created); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out.CreatedAt = created.Format(time.RFC3339)
		writeJSON(w, 200, out)
	default:
		writeErr(w, 405, "method not allowed")
	}
}

// --- web UI ---

type weightRowUI struct {
	ID       int64
	Date     string
	WeightKg float64
}

type weightChartPt struct {
	D string  `json:"d"`
	W float64 `json:"w"`
}

type weightsPage struct {
	Shell    uiShell
	Weights  []weightRowUI
	Points   []weightChartPt
	Today    string
	Page     int
	PageSize int
	Total    int
	HasPrev  bool
	HasNext  bool
	PrevPage int
	NextPage int
	PageFrom int
	PageTo   int
}

func (s *Server) uiWeights(w http.ResponseWriter, r *http.Request) {
	userID, err := s.resolveUserID(r)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	// Chart uses all points (oldest -> newest).
	rows, err := s.DB.QueryContext(r.Context(),
		`SELECT id, weight_kg, weight_date FROM weights
		 WHERE user_id = $1 ORDER BY weight_date ASC, id ASC`, userID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	all := []weightRowUI{}
	pts := []weightChartPt{}
	for rows.Next() {
		var ww weightRowUI
		if err := rows.Scan(&ww.ID, &ww.WeightKg, &ww.Date); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		all = append(all, ww)
		pts = append(pts, weightChartPt{D: ww.Date, W: ww.WeightKg})
	}
	// Newest first for the table, then paginate.
	tableAll := make([]weightRowUI, len(all))
	for i, v := range all {
		tableAll[len(all)-1-i] = v
	}
	const pageSize = 25
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	total := len(tableAll)
	start := (page - 1) * pageSize
	if start > total {
		start = total
	}
	end := start + pageSize
	if end > total {
		end = total
	}
	pageList := tableAll[start:end]
	hasPrev := page > 1
	hasNext := end < total
	render(w, "weights.html", weightsPage{
		Shell:    uiShell{ActiveTab: "weights", UserID: userID, Title: "Weight"},
		Weights:  pageList,
		Points:   pts,
		Today:    time.Now().Format("2006-01-02"),
		Page:     page,
		PageSize: pageSize,
		Total:    total,
		HasPrev:  hasPrev,
		HasNext:  hasNext,
		PrevPage: page - 1,
		NextPage: page + 1,
		PageFrom: start + 1,
		PageTo:   end,
	})
}

func (s *Server) uiWeightDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "bad id", 400)
		return
	}
	if _, err := s.DB.ExecContext(r.Context(), `DELETE FROM weights WHERE id = $1`, id); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/weights", http.StatusSeeOther)
}

func (s *Server) uiWeightUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "bad id", 400)
		return
	}
	kg, err := strconv.ParseFloat(strings.TrimSpace(r.FormValue("weight_kg")), 64)
	if err != nil || kg <= 0 {
		http.Error(w, "bad weight_kg", 400)
		return
	}
	date := strings.TrimSpace(r.FormValue("weight_date"))
	if date == "" {
		http.Error(w, "bad date", 400)
		return
	}
	if _, err := s.DB.ExecContext(r.Context(),
		`UPDATE weights SET weight_kg = $1, weight_date = $2 WHERE id = $3`,
		kg, date, id); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	redirect := "/ui/weights"
	if p := strings.TrimSpace(r.FormValue("page")); p != "" {
		redirect += "?page=" + p
	}
	http.Redirect(w, r, redirect, http.StatusSeeOther)
}

func (s *Server) uiWeightCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	userID, _ := s.resolveUserID(r)
	kg, err := strconv.ParseFloat(strings.TrimSpace(r.FormValue("weight_kg")), 64)
	if err != nil || kg <= 0 {
		http.Error(w, "bad weight_kg", 400)
		return
	}
	date := strings.TrimSpace(r.FormValue("weight_date"))
	if date == "" {
		date = time.Now().Format("2006-01-02")
	}
	if _, err := s.DB.ExecContext(r.Context(),
		`INSERT INTO weights (user_id, weight_kg, weight_date) VALUES ($1, $2, $3)`,
		userID, kg, date); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	http.Redirect(w, r, "/ui/weights", http.StatusSeeOther)
}
