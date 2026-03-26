package com.example.sonicLens.domain.song;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.integration.spotify.SpotifyFullAudioService;
import com.example.sonicLens.integration.spotify.SpotifySearchClient;
import com.example.sonicLens.integration.spotify.SpotifyTrackDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final FingerprintService fingerprintService;
    private final SpotifySearchClient spotifySearchClient;
    private final SpotifyFullAudioService spotifyFullAudioService;

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

        // Save song record first (fingerprinting references the song ID)
        // Audio lives on Spotify — no need to store it in GCS
        Song song = Song.builder()
                .title(dto.name())
                .artist(dto.artistName())
                .album(dto.albumName())
                .albumArtUrl(dto.albumArtUrl())
                .spotifyTrackId(dto.spotifyId())
                .spotifyPreviewUrl(dto.previewUrl())
                .spotifyUrl(dto.spotifyUrl())
                .durationMs(dto.durationMs())
                .trackNumber(dto.trackNumber())
                .discNumber(dto.discNumber())
                .explicit(dto.explicit())
                .isrc(dto.isrc())
                .albumSpotifyId(dto.albumSpotifyId())
                .albumType(dto.albumType())
                .releaseDate(dto.releaseDate())
                .build();
        song = songRepository.save(song);

        // Stream directly from Spotify into the fingerprinter — no buffering, no GCS upload
        try (InputStream audioStream = spotifyFullAudioService.streamTrack(spotifyTrackId)) {
            fingerprintService.fingerprintSong(song, audioStream);
        }

        return song;
    }

    // -------------------------------------------------------------------------
    // Add songs from a Spotify URL — supports track, album, and playlist URLs.
    // Always returns a list (single-element for tracks, multi for albums/playlists).
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
            case "playlist" -> {
                List<SpotifyTrackDto> playlistTracks = spotifySearchClient.getPlaylistTracks(parsed.id());
                if (playlistTracks.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No tracks found for playlist: " + parsed.id());
                }
                yield playlistTracks;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "URL must point to a Spotify track, album, or playlist");
        };

        return processTrackDtos(tracks);
    }

    // -------------------------------------------------------------------------
    // Bulk seed the catalog from Spotify's featured playlists, new releases,
    // or a free-text search query.
    // -------------------------------------------------------------------------

    public enum SeedStrategy { FEATURED_PLAYLISTS, NEW_RELEASES, SEARCH }

    public List<Song> seedFromSpotify(SeedStrategy strategy, int limit, String query) throws Exception {
        List<SpotifyTrackDto> allTracks = new ArrayList<>();

        switch (strategy) {
            case FEATURED_PLAYLISTS -> {
                // Spotify restricted playlist-tracks to user OAuth; client-credentials can't access them.
                // Fall back to searching popular queries to approximate "featured" content.
                List<String> queries = List.of("top hits", "pop hits", "trending");
                int perQuery = Math.max(1, limit / queries.size());
                for (String q : queries) {
                    allTracks.addAll(spotifySearchClient.searchTracks(q, perQuery));
                }
            }
            case NEW_RELEASES -> {
                List<String> albumIds = spotifySearchClient.getNewReleaseAlbumIds(limit);
                for (String aid : albumIds) {
                    allTracks.addAll(spotifySearchClient.getAlbumTracks(aid));
                }
            }
            case SEARCH -> {
                if (query == null || query.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "query is required for SEARCH strategy");
                }
                allTracks.addAll(spotifySearchClient.searchTracks(query, limit));
            }
        }

        return processTrackDtos(allTracks);
    }

    // Shared inner loop: skip duplicates, skip no-preview, fingerprint each track.
    private List<Song> processTrackDtos(List<SpotifyTrackDto> dtos) throws Exception {
        List<Song> result = new ArrayList<>();
        for (SpotifyTrackDto dto : dtos) {
            Optional<Song> existing = songRepository.findBySpotifyTrackId(dto.spotifyId());
            if (existing.isPresent()) {
                result.add(existing.get());
                continue;
            }
            try {
                result.add(addFromSpotify(dto.spotifyId()));
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    songRepository.findBySpotifyTrackId(dto.spotifyId()).ifPresent(result::add);
                }
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

        Song song = Song.builder()
                .title(title)
                .artist(artist)
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
