package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.domain.song.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/librespot")
@RequiredArgsConstructor
@Slf4j
public class LibrespotAdminController {

    private final SpotifyFullAudioService spotifyFullAudioService;
    private final SongService songService;

    /** Check whether the librespot session is active. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("ready", spotifyFullAudioService.isReady());
    }

    /**
     * Re-fingerprint all songs in the catalog using current algorithm parameters.
     * Deletes existing fingerprints and re-streams each track via the Go service.
     * Run this after tuning TARGET_ZONE_MAX / TARGET_ZONE_DFREQ / fan-out.
     * This is a long-running operation (~30s per song) — runs in a background thread.
     */
    @PostMapping("/refingerprint")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> refingerprint() {
        Thread t = new Thread(() -> {
            log.info("re-fingerprint started for all songs");
            List<String> results = songService.refingerprintAll();
            results.forEach(r -> log.info("refingerprint: {}", r));
            log.info("re-fingerprint complete");
        }, "refingerprint-all");
        t.setDaemon(true);
        t.start();
        return Map.of("message", "Re-fingerprinting started in background — watch server logs for progress");
    }

    /**
     * Trigger a new OAuth login — opens a browser window on the server machine.
     * Use this if the session dropped or credentials were cleared.
     */
    @PostMapping("/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> reconnect() {
        Thread t = new Thread(() -> {
            try {
                spotifyFullAudioService.reconnect();
                log.info("librespot session reconnected successfully");
            } catch (Exception e) {
                log.error("librespot reconnect failed: {}", e.getMessage(), e);
            }
        }, "librespot-reconnect");
        t.setDaemon(true);
        t.start();
        return Map.of("message", "OAuth browser login initiated — check server for browser window");
    }
}
