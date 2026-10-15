package main

import (
	"math"
	"time"
)

type Split struct {
	Km      int     `json:"km"`
	Seconds float64 `json:"seconds"`
}

const earthRadiusM = 6371000.0

func haversineM(lat1, lon1, lat2, lon2 float64) float64 {
	toRad := func(d float64) float64 { return d * math.Pi / 180 }
	dLat := toRad(lat2 - lat1)
	dLon := toRad(lon2 - lon1)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(toRad(lat1))*math.Cos(toRad(lat2))*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * earthRadiusM * math.Asin(math.Sqrt(a))
}

// computeSplits walks the track and emits per-kilometer durations by
// linearly interpolating the moment each km boundary is crossed.
func computeSplits(points []TrackPoint) (splits []Split, totalMeters float64) {
	if len(points) < 2 {
		return nil, 0
	}
	const kmM = 1000.0
	nextKm := 1
	kmStartTime := points[0].RecordedAt
	cumM := 0.0
	for i := 1; i < len(points); i++ {
		p0, p1 := points[i-1], points[i]
		d := haversineM(p0.Lat, p0.Lon, p1.Lat, p1.Lon)
		if d <= 0 {
			continue
		}
		segStart := cumM
		segEnd := cumM + d
		segSecs := p1.RecordedAt.Sub(p0.RecordedAt).Seconds()
		for float64(nextKm)*kmM <= segEnd {
			frac := (float64(nextKm)*kmM - segStart) / (segEnd - segStart)
			boundary := p0.RecordedAt.Add(time.Duration(frac * segSecs * float64(time.Second)))
			splits = append(splits, Split{Km: nextKm, Seconds: boundary.Sub(kmStartTime).Seconds()})
			kmStartTime = boundary
			nextKm++
		}
		cumM = segEnd
	}
	return splits, cumM
}
