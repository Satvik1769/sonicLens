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
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"

	librespot "github.com/devgianlu/go-librespot"
	"github.com/devgianlu/go-librespot/audio"
	"github.com/devgianlu/go-librespot/player"
	connectpb "github.com/devgianlu/go-librespot/proto/spotify/connectstate"
	devicespb "github.com/devgianlu/go-librespot/proto/spotify/connectstate/devices"
	metadatapb "github.com/devgianlu/go-librespot/proto/spotify/metadata"
	"github.com/devgianlu/go-librespot/session"
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
	mu         sync.RWMutex
	sess       *session.Session
	spotPlayer *player.Player
	isReady    atomic.Bool
	credsDir   = filepath.Join(mustHomeDir(), ".sonicLens", "go-librespot")
	credsFile  = filepath.Join(credsDir, "credentials.json")
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
// No-op EventManager — session.Events() is always nil without daemon state;
// passing nil to the player causes a nil-deref inside NewStream.
// ---------------------------------------------------------------------------

type nullEvents struct{}

func (nullEvents) PreStreamLoadNew([]byte, librespot.SpotifyId, int64) {}
func (nullEvents) PostStreamResolveAudioFile([]byte, int32, *librespot.Media, *metadatapb.AudioFile) {
}
func (nullEvents) PostStreamRequestAudioKey([]byte)                               {}
func (nullEvents) PostStreamResolveStorage([]byte)                                {}
func (nullEvents) PostStreamInitHttpChunkReader([]byte, *audio.HttpChunkedReader) {}
func (nullEvents) OnPrimaryStreamUnload(*player.Stream, int64)                    {}
func (nullEvents) PostPrimaryStreamLoad(*player.Stream, bool)                     {}
func (nullEvents) OnPlayerPlay(*player.Stream, string, bool, *connectpb.PlayOrigin, *connectpb.ProvidedTrack, int64) {
}
func (nullEvents) OnPlayerResume(*player.Stream, int64) {}
func (nullEvents) OnPlayerPause(*player.Stream, string, bool, *connectpb.PlayOrigin, *connectpb.ProvidedTrack, int64) {
}
func (nullEvents) OnPlayerSeek(*player.Stream, int64, int64)       {}
func (nullEvents) OnPlayerSkipForward(*player.Stream, int64, bool) {}
func (nullEvents) OnPlayerSkipBackward(*player.Stream, int64)      {}
func (nullEvents) OnPlayerEnd(*player.Stream, int64)               {}
func (nullEvents) Close()                                          {}

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
		creds = *stored // value type — session switch matches StoredCredentials, not *StoredCredentials
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
		// Stored credentials are stale — delete them and retry with fresh OAuth login.
		if strings.Contains(err.Error(), "BadCredentials") {
			log.Printf("stored credentials rejected (BadCredentials) — deleting and retrying with OAuth")
			_ = os.Remove(credsFile)
			opts.Credentials = session.InteractiveCredentials{CallbackPort: oauthPort}
			newSess, err = session.NewSessionFromOptions(context.Background(), opts)
		}
		if err != nil {
			return fmt.Errorf("session creation failed: %w", err)
		}
	}

	saveCredentials(newSess.Username(), newSess.StoredCredentials())

	countryCode := ""
	p, err := player.NewPlayer(&player.Options{
		Log:                  logger,
		Spclient:             newSess.Spclient(),
		AudioKey:             newSess.AudioKey(),
		Events:               nullEvents{},
		CountryCode:          &countryCode,
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
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("[PANIC] handleStream: %v\n%s", rec, debug.Stack())
			http.Error(w, fmt.Sprintf("internal panic: %v", rec), http.StatusInternalServerError)
		}
	}()

	if !isReady.Load() {
		http.Error(w, "librespot session not ready", http.StatusServiceUnavailable)
		return
	}

	trackId := r.PathValue("trackId")
	log.Printf("stream request for track %s", trackId)

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
		log.Printf("NewStream error for track %s: %v", trackId, err)
		http.Error(w, "failed to load track: "+err.Error(), http.StatusInternalServerError)
		return
	}
	log.Printf("stream opened for track %s", trackId)

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
				log.Printf("audio read error for track %s: %v", trackId, readErr)
				http.Error(w, "audio read error: "+readErr.Error(), http.StatusInternalServerError)
				return
			}
			log.Printf("stream read stopped for track %s: %v", trackId, readErr)
			break
		}
	}
	log.Printf("track %s: read %d samples, writing WAV", trackId, len(allSamples))

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

	// Build the 44-byte WAV header in one shot.
	var hdr [44]byte
	copy(hdr[0:], "RIFF")
	le.PutUint32(hdr[4:], 36+dataSize)
	copy(hdr[8:], "WAVE")
	copy(hdr[12:], "fmt ")
	le.PutUint32(hdr[16:], 16) // fmt chunk size
	le.PutUint16(hdr[20:], 1)  // PCM
	le.PutUint16(hdr[22:], uint16(channels))
	le.PutUint32(hdr[24:], uint32(sampleRate))
	le.PutUint32(hdr[28:], uint32(sampleRate*channels*2)) // byte rate
	le.PutUint16(hdr[32:], uint16(channels*2))            // block align
	le.PutUint16(hdr[34:], 16)                            // bits per sample
	copy(hdr[36:], "data")
	le.PutUint32(hdr[40:], dataSize)
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}

	// Convert float32 → int16 into a pre-allocated byte slice, write once.
	pcm := make([]byte, len(samples)*2)
	for i, s := range samples {
		if s > 1.0 {
			s = 1.0
		} else if s < -1.0 {
			s = -1.0
		}
		le.PutUint16(pcm[i*2:], uint16(int16(s*32767)))
	}
	_, err := w.Write(pcm)
	return err
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
