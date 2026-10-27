package main

import (
	"context"
	"database/sql"
	_ "embed"
	"flag"
	"log"
	"net/http"
	"strings"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	_ "modernc.org/sqlite"

	"traxxx/server/db/dbstore"
)

//go:embed db/schema.pg.sql
var schemaPG string

//go:embed db/schema.sqlite.sql
var schemaSqlite string

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dbPath := flag.String("db", "traxxx.db", "database location — sqlite file path, or postgres://… URL")
	seed := flag.Bool("seed", false, "wipe and seed fake data, then exit")
	flag.Parse()

	db, pg, err := openDB(*dbPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()

	schema := schemaSqlite
	if pg {
		schema = schemaPG
	}
	if _, err := db.Exec(schema); err != nil {
		log.Fatalf("apply schema: %v", err)
	}
	if err := ensureClientUUIDColumn(db, pg); err != nil {
		log.Fatalf("migrate client_uuid: %v", err)
	}

	q := dbstore.New(db)

	if *seed {
		seedFakeData(db, q)
		return
	}

	srv := &Server{DB: db, Q: q}
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

func openDB(dsn string) (*sql.DB, bool, error) {
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		db, err := sql.Open("pgx", dsn)
		return db, true, err
	}
	db, err := sql.Open("sqlite", dsn+"?_pragma=foreign_keys(1)")
	return db, false, err
}

// ensureClientUUIDColumn adds the activities.client_uuid column on databases
// created before it was in the schema. Idempotent on both engines.
func ensureClientUUIDColumn(db *sql.DB, pg bool) error {
	if pg {
		if _, err := db.Exec(`ALTER TABLE activities ADD COLUMN IF NOT EXISTS client_uuid TEXT`); err != nil {
			return err
		}
		_, err := db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_activities_client_uuid
			ON activities(client_uuid) WHERE client_uuid IS NOT NULL`)
		return err
	}
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

var _ context.Context = context.TODO()

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
