# syntax=docker/dockerfile:1.7

# ---- build stage ----------------------------------------------------------
FROM golang:1.22-alpine AS build

WORKDIR /src

# Cache deps first.
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY . .

# modernc.org/sqlite is pure Go, so we can build fully static without CGO.
ENV CGO_ENABLED=0 GOOS=linux
ARG TARGETARCH
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    GOARCH=${TARGETARCH:-amd64} \
    go build -trimpath -ldflags="-s -w" -o /out/traxxx-server .

# ---- runtime stage --------------------------------------------------------
FROM alpine:3.20

# tini keeps PID 1 well-behaved (handles signals, reaps zombies).
RUN apk add --no-cache ca-certificates tini \
 && addgroup -S traxxx \
 && adduser -S -G traxxx -h /data traxxx \
 && mkdir -p /data \
 && chown -R traxxx:traxxx /data

COPY --from=build /out/traxxx-server /usr/local/bin/traxxx-server

USER traxxx
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8080

ENV TRAXXX_ADDR=":8080" \
    TRAXXX_DB="/data/traxxx.db"

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/traxxx-server"]
CMD ["--addr", ":8080", "--db", "/data/traxxx.db"]
