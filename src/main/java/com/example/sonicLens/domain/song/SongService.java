package com.example.sonicLens.domain.song;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.integration.spotify.SpotifySearchClient;
import com.example.sonicLens.integration.spotify.SpotifyTrackDto;
import com.example.sonicLens.storage.GcsStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final FingerprintService fingerprintService;
    private final SpotifySearchClient spotifySearchClient;
    private final GcsStorageService gcsStorageService;

    // -------------------------------------------------------------------------
    // Add a song directly from Spotify (no file upload needed)
    // Downloads the 30s preview, fingerprints it, stores metadata.
    // -------------------------------------------------------------------------

    @Transactional
    public Song addFromSpotify(String spotifyTrackId) throws Exception {
        // Prevent duplicate entries
        songRepository.findBySpotifyTrackId(spotifyTrackId).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Song already in catalog: " + existing.getTitle());
        });

        SpotifyTrackDto dto = spotifySearchClient.getTrack(spotifyTrackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Spotify track not found: " + spotifyTrackId));

        if (dto.previewUrl() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This track has no 30s preview available on Spotify");
        }

        // Download the 30s preview MP3
        byte[] previewBytes = spotifySearchClient.downloadPreview(dto.previewUrl());

        // Upload preview to GCS for later playback reference
        String objectName = "audio/spotify_" + spotifyTrackId + ".mp3";
        gcsStorageService.upload(previewBytes, objectName, "audio/mpeg");

        // Save song record
        Song song = Song.builder()
                .title(dto.name())
                .artist(dto.artistName())
                .album(dto.albumName())
                .albumArtUrl(dto.albumArtUrl())
                .spotifyTrackId(dto.spotifyId())
                .spotifyPreviewUrl(dto.previewUrl())
                .durationMs(dto.durationMs())
                .filePath(objectName)
                .build();
        song = songRepository.save(song);

        // Fingerprint the 30s preview
        try (ByteArrayInputStream audioIn = new ByteArrayInputStream(previewBytes)) {
            fingerprintService.fingerprintSong(song, audioIn);
        }

        return song;
    }

    // -------------------------------------------------------------------------
    // Add songs from a Spotify URL — supports both track and album URLs.
    // Always returns a list (single-element for tracks, multi for albums).
    // Tracks with no 30s preview are silently skipped (cannot be fingerprinted).
    // Already-catalogued tracks are included in the result without re-processing.
    // -------------------------------------------------------------------------

    public List<Song> addFromSpotifyUrl(String url) throws Exception {
        SpotifySearchClient.SpotifyUrlParsed parsed = spotifySearchClient.parseSpotifyUrl(url);

        List<SpotifyTrackDto> tracks = switch (parsed.type()) {
            case "track" -> {
                SpotifyTrackDto dto = spotifySearchClient.getTrack(parsed.id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Spotify track not found: " + parsed.id()));
                yield List.of(dto);
            }
            case "album" -> {
                List<SpotifyTrackDto> albumTracks = spotifySearchClient.getAlbumTracks(parsed.id());
                if (albumTracks.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No tracks found for album: " + parsed.id());
                }
                yield albumTracks;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "URL must point to a Spotify track or album");
        };

        List<Song> result = new ArrayList<>();
        for (SpotifyTrackDto dto : tracks) {
            // Already in catalog — include without re-fingerprinting
            Optional<Song> existing = songRepository.findBySpotifyTrackId(dto.spotifyId());
            if (existing.isPresent()) {
                result.add(existing.get());
                continue;
            }

            // Skip tracks that have no preview (cannot fingerprint without audio)
            if (dto.previewUrl() == null) continue;

            try {
                result.add(addFromSpotify(dto.spotifyId()));
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    songRepository.findBySpotifyTrackId(dto.spotifyId()).ifPresent(result::add);
                }
                // other errors (e.g. download failure) — skip track
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Search Spotify catalog (returns candidates for the user to pick from)
    // -------------------------------------------------------------------------

    public List<SpotifyTrackDto> searchSpotify(String query) {
        return spotifySearchClient.searchTracks(query, 10);
    }

    // -------------------------------------------------------------------------
    // Manual upload (custom/local audio files)
    // -------------------------------------------------------------------------

    @Transactional
    public Song uploadSong(MultipartFile file, String title, String artist) throws Exception {
        byte[] audioBytes = file.getBytes();
        String filename = UUID.randomUUID() + "_" +
                StringUtils.cleanPath(file.getOriginalFilename() != null
                        ? file.getOriginalFilename() : "audio.wav");
        String objectName = "audio/" + filename;

        String contentType = file.getContentType() != null ? file.getContentType() : "audio/wav";
        gcsStorageService.upload(audioBytes, objectName, contentType);

        Song song = Song.builder()
                .title(title)
                .artist(artist)
                .filePath(objectName)
                .build();
        song = songRepository.save(song);

        try (ByteArrayInputStream audioIn = new ByteArrayInputStream(audioBytes)) {
            fingerprintService.fingerprintSong(song, audioIn);
        }

        // Try to enrich with Spotify metadata
        Optional<SpotifyTrackDto> spotifyData = spotifySearchClient.searchTrack(title, artist);
        if (spotifyData.isPresent()) {
            SpotifyTrackDto dto = spotifyData.get();
            song.setAlbum(dto.albumName());
            song.setAlbumArtUrl(dto.albumArtUrl());
            song.setSpotifyTrackId(dto.spotifyId());
            song.setSpotifyPreviewUrl(dto.previewUrl());
            song.setDurationMs(dto.durationMs());
            song = songRepository.save(song);
        }

        return song;
    }

    public List<Song> listAll() {
        return songRepository.findAll();
    }

    public Song getById(Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
    }
}
