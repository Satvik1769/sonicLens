package com.example.sonicLens.domain.song;

import com.example.sonicLens.integration.spotify.SpotifyTrackDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;

    /**
     * Add a song to the catalog by Spotify track ID.
     * Downloads the 30s preview, fingerprints it, stores metadata.
     * Body: { "spotifyTrackId": "3n3Ppam7vgaVa1iaRUIOKE" }
     */
    @PostMapping("/add")
    @ResponseStatus(HttpStatus.CREATED)
    public Song addFromSpotify(@RequestBody AddFromSpotifyRequest req) throws Exception {
        return songService.addFromSpotify(req.spotifyTrackId());
    }

    /**
     * Add songs from a Spotify URL (track or album).
     * - Track URL  → fingerprints and stores that one song.
     * - Album URL  → fingerprints every track in the album that has a preview.
     * Returns the list of songs added (or already present in the catalog).
     * Tracks with no Spotify 30s preview are silently skipped.
     *
     * Body: { "url": "https://open.spotify.com/album/..." }
     *       { "url": "https://open.spotify.com/track/..." }
     *       { "url": "spotify:album:..." }
     *       { "url": "spotify:track:..." }
     */
    @PostMapping("/add-url")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Song> addFromUrl(@RequestBody AddFromUrlRequest req) throws Exception {
        return songService.addFromSpotifyUrl(req.url());
    }

    /**
     * Search the Spotify catalog. Returns up to 10 candidates.
     * Use the spotifyTrackId from results to call POST /songs/add.
     * Example: GET /songs/search?q=Bohemian+Rhapsody+Queen
     */
    @GetMapping("/search")
    public List<SpotifyTrackDto> search(@RequestParam String q) {
        return songService.searchSpotify(q);
    }

    /**
     * Manual upload of a local audio file (WAV/MP3).
     * Use when the track isn't on Spotify or has no preview.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Song upload(@RequestPart("file") MultipartFile file,
                       @RequestPart("title") String title,
                       @RequestPart("artist") String artist) throws Exception {
        return songService.uploadSong(file, title, artist);
    }

    /**
     * Bulk-seed the catalog from Spotify's catalog.
     * Strategies:
     *   FEATURED_PLAYLISTS — pulls Spotify's featured playlists (limit = number of playlists)
     *   NEW_RELEASES       — pulls new album releases     (limit = number of albums)
     *   SEARCH             — free-text search             (limit = number of tracks, query required)
     *
     * Examples:
     *   { "strategy": "FEATURED_PLAYLISTS", "limit": 5 }
     *   { "strategy": "NEW_RELEASES", "limit": 10 }
     *   { "strategy": "SEARCH", "query": "pop hits 2024", "limit": 50 }
     */
    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Song> seed(@RequestBody SeedRequest req) throws Exception {
        return songService.seedFromSpotify(req.strategy(), req.limit(), req.query());
    }

    @GetMapping
    public List<Song> list() {
        return songService.listAll();
    }

    @GetMapping("/get/{id}")
    public Song get(@PathVariable Long id) {
        return songService.getById(id);
    }

    public record AddFromSpotifyRequest(String spotifyTrackId) {}
    public record AddFromUrlRequest(String url) {}
    public record SeedRequest(SongService.SeedStrategy strategy, int limit, String query) {}
}
