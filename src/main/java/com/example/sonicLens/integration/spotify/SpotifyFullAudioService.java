package com.example.sonicLens.integration.spotify;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
@Slf4j
public class SpotifyFullAudioService {

    @Value("${librespot.service.url:http://localhost:8888}")
    private String serviceUrl;

    @PostConstruct
    public void init() {
        if (isReady()) {
            log.info("librespot Go service is ready at {}", serviceUrl);
        } else {
            log.warn("librespot Go service is not ready at {}. Ensure librespot-service is running.", serviceUrl);
        }
    }

    public boolean isReady() {
        try {
            HttpURLConnection conn = openConnection("/status", "GET");
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(3_000);
            if (conn.getResponseCode() != 200) return false;
            String body = new String(conn.getInputStream().readAllBytes());
            return body.contains("\"ready\":true");
        } catch (Exception e) {
            return false;
        }
    }

    public void reconnect() throws Exception {
        HttpURLConnection conn = openConnection("/reconnect", "POST");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        int status = conn.getResponseCode();
        if (status != 202) {
            throw new RuntimeException("librespot reconnect returned HTTP " + status);
        }
    }

    /**
     * Returns a WAV InputStream for the given Spotify base62 track ID.
     * The caller is responsible for closing the stream.
     */
    public InputStream streamTrack(String spotifyTrackId) throws Exception {
        HttpURLConnection conn = openConnection("/stream/" + spotifyTrackId, "GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(300_000); // full track may take time to buffer
        int status = conn.getResponseCode();
        if (status != 200) {
            String body = new String(conn.getErrorStream().readAllBytes());
            log.error("librespot service returned HTTP {}: {}", status, body);
            throw new RuntimeException("librespot service returned HTTP " + status + ": " + body);
        }
        return conn.getInputStream();
    }

    private HttpURLConnection openConnection(String path, String method) throws Exception {
        URL url = new URL(serviceUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        return conn;
    }
}