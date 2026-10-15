package main

import (
	"database/sql"
	_ "embed"
	"flag"
	"log"
	"net/http"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dbPath := flag.String("db", "traxxx.db", "sqlite database path")
	seed := flag.Bool("seed", false, "wipe and seed fake data, then exit")
	flag.Parse()

	db, err := sql.Open("sqlite", *dbPath+"?_pragma=foreign_keys(1)")
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()

	if _, err := db.Exec(schemaSQL); err != nil {
		log.Fatalf("apply schema: %v", err)
	}
	if err := ensureClientUUIDColumn(db); err != nil {
		log.Fatalf("migrate client_uuid: %v", err)
	}

	if *seed {
		seedFakeData(db)
		return
	}

	srv := &Server{DB: db}
	mux := http.NewServeMux()
	srv.Routes(mux)

	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           withCORS(withLogging(mux)),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("traxxx server listening on %s (db=%s)", *addr, *dbPath)
	log.Fatal(httpSrv.ListenAndServe())
}

// ensureClientUUIDColumn adds the activities.client_uuid column on databases
// created before it was added to schema.sql. SQLite has no "ADD COLUMN IF NOT
// EXISTS", so we check table_info first. The partial unique index itself is
// idempotent (IF NOT EXISTS) and already in schema.sql.
func ensureClientUUIDColumn(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(activities)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	has := false
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == "client_uuid" {
			has = true
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if !has {
		if _, err := db.Exec(`ALTER TABLE activities ADD COLUMN client_uuid TEXT`); err != nil {
			return err
		}
		if _, err := db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_activities_client_uuid
			ON activities(client_uuid) WHERE client_uuid IS NOT NULL`); err != nil {
			return err
		}
	}
	return nil
}

func withLogging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start))
	})
}

func withCORS(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		h.ServeHTTP(w, r)
	})
}
