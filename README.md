# sonicLens

An audio recognition and music discovery system using audio fingerprinting to identify songs from a Spotify-backed catalog — similar to Shazam, but self-hosted.

## Overview

sonicLens lets you upload an audio clip and identify the matching song from a catalog you build from Spotify. It uses FFT-based combinatorial hashing for fingerprinting, similar to the Shazam algorithm.

**Key features:**
- Audio recognition with confidence scoring
- Song catalog built from Spotify tracks, albums, or playlists
- Full-track audio streaming via a Go/librespot microservice
- Spotify OAuth integration for user authentication and playback
- Recognition history per user
- JWT-authenticated REST API

## Architecture

```
┌─────────────────────────────────┐
│  Client (Browser / API consumer)│
└──────────────┬──────────────────┘
               │
   ┌───────────▼────────────┐     ┌─────────────────────┐
   │  Spring Boot REST API  │────▶│  Go Microservice    │
   │  Java · Port 8082      │     │  Port 8888          │
   │                        │◀────│                     │
   │  - JWT auth            │     │  - Spotify full-    │
   │  - Audio fingerprinting│     │    track streaming  │
   │  - Recognition logic   │     │  - librespot OAuth  │
   │  - Spotify API bridge  │     │  - WAV encoding     │
   │  - Song/history mgmt   │     └─────────────────────┘
   └───────┬────────────────┘
           │
     ┌─────┴──────┐   ┌───────────────────┐
     │ PostgreSQL │   │  GCP Cloud Storage│
     └────────────┘   └───────────────────┘
```

## Prerequisites

- Java 17+
- Go 1.21+
- PostgreSQL
- `pkg-config` and librespot native dependencies (see Go service setup)
- A Spotify Developer app (Client ID + Secret)
- (Optional) GCP project with Cloud Storage

## Getting Started

### 1. Go Microservice (librespot-service)

The Go service handles full-track audio streaming from Spotify. It must be running before the Spring Boot app starts.

```bash
cd librespot-service
PKG_CONFIG_PATH="/opt/homebrew/lib/pkgconfig" go run .
```

On first run, a browser window opens for Spotify OAuth login. Credentials are saved to `~/.sonicLens/go-librespot/credentials.json` and reused on subsequent starts.

**Run with pm2 (recommended for persistent deployments):**

```bash
# Start
pm2 start "go run ." --name librespot-service

# For faster restarts, build a binary first
go build -o librespot-service .
pm2 start ./librespot-service --name librespot-service
```

**Useful pm2 commands:**
```bash
pm2 list                        # see all running services
pm2 logs librespot-service      # tail logs
pm2 restart librespot-service   # restart
pm2 stop librespot-service      # stop
```

### 2. Spring Boot API

Configure `src/main/resources/application.properties`:

```properties
# Database
spring.datasource.url=jdbc:postgresql://<host>:5432/<db>
spring.datasource.username=<user>
spring.datasource.password=<password>

# Spotify
spotify.client-id=<your-client-id>
spotify.client-secret=<your-client-secret>
spotify.redirect-uri=http://<host>:8082/callback

# Go service (must match where librespot-service is running)
librespot.service.url=http://localhost:8888

# JWT
jwt.secret=<your-secret>

# GCP (optional)
gcp.storage.bucket-name=<bucket>
gcp.storage.project-id=<project>
```

Then build and run:

```bash
./gradlew bootRun
```

The API will be available on port `8082`.

## API Reference

### Authentication

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/auth/register` | — | Create a user account |
| POST | `/auth/login` | — | Get a JWT token |
| GET | `/auth/spotify` | JWT | Get Spotify OAuth authorization URL |
| GET | `/callback` | — | Spotify OAuth redirect handler |
| GET | `/auth/spotify/token` | JWT | Get/refresh Spotify access token |

### Song Catalog

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/songs/add` | JWT | Add song by Spotify track ID |
| POST | `/songs/add-url` | JWT | Add from Spotify track/album/playlist URL |
| GET | `/songs/search?q=` | — | Search Spotify |
| POST | `/songs/upload` | JWT | Upload a local audio file |
| POST | `/songs/seed` | JWT | Bulk-seed catalog from Spotify featured/new releases |
| GET | `/songs` | — | List all songs in catalog |
| GET | `/songs/get/{id}` | — | Get song by ID |

### Recognition

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/recognize` | JWT | Upload an audio clip for identification |

### History

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/history` | JWT | Get current user's recognition history |
| DELETE | `/history/{id}` | JWT | Delete a history entry |

### Users

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/users/me` | JWT | Get current user info |
| GET | `/users/{id}` | — | Get user by ID |

### Admin

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/admin/librespot/status` | — | Check Go service readiness |
| POST | `/admin/librespot/reconnect` | — | Trigger Spotify re-authentication |

## Fingerprinting Algorithm

sonicLens uses a Shazam-style combinatorial hash fingerprinting approach:

1. **Decode** any audio format to mono 16-bit PCM at 11,025 Hz
2. **Spectrogram** — 4,096-sample FFT with 50% overlap and Hann window
3. **Peak detection** — find peak magnitude across 6 log-scale frequency bands (40 Hz – 4 kHz)
4. **Combinatorial hashing** — pair each peak (anchor) with nearby peaks (target) within a time zone to create a hash encoding anchor frequency, target frequency, and time delta
5. **Recognition** — query the database for matching hashes, vote by `(songId, timeDelta)`, and declare the winner above a 5% confidence threshold

## Go Microservice Endpoints

The librespot service runs on port `8888`:

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/status` | Returns `{"ready": bool}` |
| POST | `/reconnect` | Clears credentials and triggers new OAuth login |
| GET | `/stream/{trackId}` | Streams full track as WAV (44.1 kHz, stereo, 16-bit PCM) |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend API | Java 17, Spring Boot, Spring Security (JWT) |
| Audio streaming | Go, go-librespot |
| Fingerprinting | JTransforms (FFT) |
| Database | PostgreSQL (JPA/Hibernate) |
| File storage | GCP Cloud Storage |
| Auth | JWT (JJWT), Spotify OAuth2 |
| Build | Gradle |
| Process mgmt | pm2 |