package main

import (
	_ "embed"
	"net/http"
	"strconv"
)

//go:embed embed/app-debug.apk
var apkBytes []byte

const apkDownloadName = "traxxx.apk"

func (s *Server) handleAPKDownload(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+apkDownloadName+`"`)
	w.Header().Set("Content-Length", strconv.Itoa(len(apkBytes)))
	_, _ = w.Write(apkBytes)
}
