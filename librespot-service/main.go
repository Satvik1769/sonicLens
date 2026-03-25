package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"

	librespot "github.com/devgianlu/go-librespot"
	"github.com/devgianlu/go-librespot/player"
	"github.com/devgianlu/go-librespot/session"
	devicespb "github.com/devgianlu/go-librespot/proto/spotify/connectstate/devices"
)

const (
	listenPort    = 8888
	oauthPort     = 8889
	streamBitrate = 160
	sampleRate    = 44100
	channels      = 2

	// 40-char hex string = 20 bytes, required by go-librespot.
	deviceId = "a3f4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"
)

var (
	mu          sync.RWMutex
	sess        *session.Session
	spotPlayer  *player.Player
	isReady     atomic.Bool
	credsDir    = filepath.Join(mustHomeDir(), ".sonicLens", "go-librespot")
	credsFile   = filepath.Join(credsDir, "credentials.json")
)

// ---------------------------------------------------------------------------
// Minimal logger that forwards to the standard log package.
// ---------------------------------------------------------------------------

type stdLogger struct{}

func (stdLogger) Tracef(f string, a ...interface{}) { log.Printf("[TRACE] "+f, a...) }
func (stdLogger) Debugf(f string, a ...interface{}) { log.Printf("[DEBUG] "+f, a...) }
func (stdLogger) Infof(f string, a ...interface{})  { log.Printf("[INFO]  "+f, a...) }
func (stdLogger) Warnf(f string, a ...interface{})  { log.Printf("[WARN]  "+f, a...) }
func (stdLogger) Errorf(f string, a ...interface{}) { log.Printf("[ERROR] "+f, a...) }

func (stdLogger) Trace(a ...interface{}) { log.Println(append([]interface{}{"[TRACE]"}, a...)...) }
func (stdLogger) Debug(a ...interface{}) { log.Println(append([]interface{}{"[DEBUG]"}, a...)...) }
func (stdLogger) Info(a ...interface{})  { log.Println(append([]interface{}{"[INFO] "}, a...)...) }
func (stdLogger) Warn(a ...interface{})  { log.Println(append([]interface{}{"[WARN] "}, a...)...) }
func (stdLogger) Error(a ...interface{}) { log.Println(append([]interface{}{"[ERROR]"}, a...)...) }

func (l stdLogger) WithField(string, interface{}) librespot.Logger { return l }
func (l stdLogger) WithError(error) librespot.Logger               { return l }

var logger librespot.Logger = stdLogger{}

// ---------------------------------------------------------------------------
// Credentials persistence
// ---------------------------------------------------------------------------

type savedCredentials struct {
	Username string `json:"username"`
	Data     []byte `json:"data"`
}

func mustHomeDir() string {
	h, err := os.UserHomeDir()
	if err != nil {
		panic(err)
	}
	return h
}

func loadSavedCredentials() *session.StoredCredentials {
	data, err := os.ReadFile(credsFile)
	if err != nil {
		return nil
	}
	var saved savedCredentials
	if err := json.Unmarshal(data, &saved); err != nil || saved.Username == "" {
		return nil
	}
	return &session.StoredCredentials{Username: saved.Username, Data: saved.Data}
}

func saveCredentials(username string, data []byte) {
	if len(data) == 0 {
		return
	}
	_ = os.MkdirAll(credsDir, 0700)
	saved := savedCredentials{Username: username, Data: data}
	b, err := json.Marshal(saved)
	if err != nil {
		return
	}
	_ = os.WriteFile(credsFile, b, 0600)
	log.Printf("credentials saved for %s", username)
}

// ---------------------------------------------------------------------------
// Session & player management
// ---------------------------------------------------------------------------

func connect() error {
	mu.Lock()
	defer mu.Unlock()

	isReady.Store(false)

	var creds any
	if stored := loadSavedCredentials(); stored != nil {
		log.Printf("loading stored credentials for %s", stored.Username)
		creds = stored
	} else {
		log.Printf("no stored credentials — starting OAuth login (callback on port %d)", oauthPort)
		creds = session.InteractiveCredentials{CallbackPort: oauthPort}
	}

	opts := &session.Options{
		Log:         logger,
		DeviceType:  devicespb.DeviceType_COMPUTER,
		DeviceId:    deviceId,
		Credentials: creds,
	}

	newSess, err := session.NewSessionFromOptions(context.Background(), opts)
	if err != nil {
		return fmt.Errorf("session creation failed: %w", err)
	}

	saveCredentials(newSess.Username(), newSess.StoredCredentials())

	p, err := player.NewPlayer(&player.Options{
		Log:                  logger,
		Spclient:             newSess.Spclient(),
		AudioKey:             newSess.AudioKey(),
		Events:               newSess.Events(),
		NormalisationEnabled: false,
	})
	if err != nil {
		newSess.Close()
		return fmt.Errorf("player creation failed: %w", err)
	}

	sess = newSess
	spotPlayer = p
	isReady.Store(true)
	log.Printf("librespot session ready for %s", newSess.Username())
	return nil
}

// ---------------------------------------------------------------------------
// HTTP handlers
// ---------------------------------------------------------------------------

func handleStatus(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]bool{"ready": isReady.Load()})
}

func handleReconnect(w http.ResponseWriter, _ *http.Request) {
	_ = os.Remove(credsFile)
	go func() {
		if err := connect(); err != nil {
			log.Printf("reconnect failed: %v", err)
		}
	}()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"message": "reconnect initiated — complete OAuth in browser if prompted",
	})
}

func handleStream(w http.ResponseWriter, r *http.Request) {
	if !isReady.Load() {
		http.Error(w, "librespot session not ready", http.StatusServiceUnavailable)
		return
	}

	trackId := r.PathValue("trackId")

	spotId, err := librespot.SpotifyIdFromBase62(librespot.SpotifyIdTypeTrack, trackId)
	if err != nil {
		http.Error(w, "invalid track id: "+err.Error(), http.StatusBadRequest)
		return
	}

	mu.RLock()
	p := spotPlayer
	mu.RUnlock()

	stream, err := p.NewStream(r.Context(), &http.Client{}, *spotId, streamBitrate, 0)
	if err != nil {
		http.Error(w, "failed to load track: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Drain all float32 PCM samples from go-librespot (stereo, 44100 Hz).
	buf := make([]float32, 8192)
	var allSamples []float32
	for {
		n, readErr := stream.Source.Read(buf)
		if n > 0 {
			allSamples = append(allSamples, buf[:n]...)
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			if len(allSamples) == 0 {
				http.Error(w, "audio read error: "+readErr.Error(), http.StatusInternalServerError)
				return
			}
			log.Printf("stream read stopped for track %s: %v", trackId, readErr)
			break
		}
	}

	w.Header().Set("Content-Type", "audio/wav")
	if err := writeWAV(w, allSamples); err != nil {
		log.Printf("WAV write error for track %s: %v", trackId, err)
	}
}

// ---------------------------------------------------------------------------
// WAV encoding  (stereo, 44100 Hz, 16-bit PCM little-endian)
// ---------------------------------------------------------------------------

func writeWAV(w io.Writer, samples []float32) error {
	dataSize := uint32(len(samples) * 2) // 16-bit = 2 bytes per sample

	le := binary.LittleEndian
	write := func(v any) error { return binary.Write(w, le, v) }
	writeTag := func(tag string) error { _, err := io.WriteString(w, tag); return err }

	// RIFF header
	if err := writeTag("RIFF"); err != nil {
		return err
	}
	if err := write(uint32(36 + dataSize)); err != nil {
		return err
	}
	if err := writeTag("WAVE"); err != nil {
		return err
	}

	// fmt chunk
	if err := writeTag("fmt "); err != nil {
		return err
	}
	for _, v := range []any{
		uint32(16),              // chunk size
		uint16(1),               // PCM
		uint16(channels),        // channel count
		uint32(sampleRate),      // sample rate
		uint32(sampleRate * channels * 2), // byte rate
		uint16(channels * 2),    // block align
		uint16(16),              // bits per sample
	} {
		if err := write(v); err != nil {
			return err
		}
	}

	// data chunk
	if err := writeTag("data"); err != nil {
		return err
	}
	if err := write(dataSize); err != nil {
		return err
	}

	// float32 → int16 PCM
	for _, s := range samples {
		if s > 1.0 {
			s = 1.0
		} else if s < -1.0 {
			s = -1.0
		}
		if err := write(int16(s * 32767)); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

func main() {
	if err := os.MkdirAll(credsDir, 0700); err != nil {
		log.Fatalf("cannot create credentials dir: %v", err)
	}

	go func() {
		if err := connect(); err != nil {
			log.Printf("initial session failed: %v", err)
		}
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /status", handleStatus)
	mux.HandleFunc("POST /reconnect", handleReconnect)
	mux.HandleFunc("GET /stream/{trackId}", handleStream)

	addr := fmt.Sprintf(":%d", listenPort)
	log.Printf("librespot-service listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}