package com.example.sonicLens.integration.spotify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/librespot")
@RequiredArgsConstructor
@Slf4j
public class LibrespotAdminController {

    private final SpotifyFullAudioService spotifyFullAudioService;

    /** Check whether the librespot session is active. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("ready", spotifyFullAudioService.isReady());
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
