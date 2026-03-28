package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.domain.song.Song;
import com.example.sonicLens.domain.song.SongService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/trending")
@RequiredArgsConstructor
public class TrendingController {

    private final SpotifySearchClient spotifySearchClient;
    private final SongService songService;

    /**
     * Returns trending tracks (as Song DB entities), chart playlists, and new releases.
     *
     * For each trending track:
     *  - Metadata is saved to the songs table immediately (returns existing if already present)
     *  - Audio fingerprinting is queued asynchronously in the background
     *
     * Query params (all optional):
     *   playlistLimit  – chart playlists to return (default 10)
     *   trackLimit     – trending tracks to return (default 10)
     *   releaseLimit   – new release albums to return (default 10)
     */
    @GetMapping
    public TrendingResponse getTrending(
            @RequestParam(defaultValue = "10") int playlistLimit,
            @RequestParam(defaultValue = "10") int trackLimit,
            @RequestParam(defaultValue = "10") int releaseLimit
    ) {
        // Fetch from Spotify
        List<SpotifyPlaylistDto> playlists = spotifySearchClient.getToplistPlaylists(playlistLimit);
        List<SpotifyTrackDto> spotifyTracks = spotifySearchClient.searchTracks("top hits", trackLimit);
        List<SpotifyAlbumDto> newReleases = spotifySearchClient.getNewReleaseAlbums(releaseLimit);

        // Save metadata to DB and queue fingerprinting async for each track
        List<Song> songs = new ArrayList<>();
        for (SpotifyTrackDto dto : spotifyTracks) {
            Song song = songService.saveMetadataOnly(dto);
            songs.add(song);
            songService.fingerprintInBackground(song);
        }

        return new TrendingResponse(songs, playlists, newReleases);
    }
}
