package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.config.SpotifyConfig;
import com.spotify.metadata.Metadata;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.audio.format.AudioQualityPicker;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.metadata.TrackId;

import java.io.InputStream;

@Service
@Slf4j
public class SpotifyFullAudioService {

    private final SpotifyConfig config;
    private Session session;

    // Picks the highest-quality Vorbis (OGG) file available
    private static final AudioQualityPicker VORBIS_HIGH = files -> {
        Metadata.AudioFile best = null;
        for (Metadata.AudioFile f : files) {
            if (SuperAudioFormat.get(f.getFormat()) == SuperAudioFormat.VORBIS) {
                if (best == null || f.getFormat().getNumber() > best.getFormat().getNumber()) {
                    best = f;
                }
            }
        }
        return best;
    };

    public SpotifyFullAudioService(SpotifyConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        String username = config.getPremium().getUsername();
        if (username == null || username.isBlank() || username.startsWith("your_")) {
            log.warn("Spotify Premium credentials not configured — full audio download disabled. " +
                     "Set spotify.premium.username and spotify.premium.password in application.properties.");
            return;
        }
        try {
            connect();
            log.info("librespot session established for: {}", username);
        } catch (Exception e) {
            log.error("Failed to initialize librespot session: {}", e.getMessage());
        }
    }

    /**
     * Opens a streaming OGG Vorbis InputStream for the full track directly from Spotify.
     * The caller is responsible for closing the stream.
     * Retries once with a fresh session if the first attempt fails.
     */
    public InputStream streamTrack(String spotifyTrackId) throws Exception {
        if (session == null) {
            throw new IllegalStateException(
                    "Spotify Premium credentials are not configured. " +
                    "Set spotify.premium.username / spotify.premium.password.");
        }
        try {
            return openStream(spotifyTrackId);
        } catch (Exception e) {
            log.warn("librespot stream failed ({}), reconnecting and retrying...", e.getMessage());
            connect();
            return openStream(spotifyTrackId);
        }
    }

    private InputStream openStream(String spotifyTrackId) throws Exception {
        TrackId trackId = TrackId.fromBase62(spotifyTrackId);

        PlayableContentFeeder.LoadedStream stream = session.contentFeeder().load(
                trackId,
                VORBIS_HIGH,
                true,
                null
        );

        return stream.in.stream();
    }

    private synchronized void connect() throws Exception {
        Session.Configuration conf = new Session.Configuration.Builder()
                .setStoreCredentials(false)
                .setCacheEnabled(false)
                .build();

        session = new Session.Builder(conf)
                .userPass(config.getPremium().getUsername(), config.getPremium().getPassword())
                .create();
    }
}
