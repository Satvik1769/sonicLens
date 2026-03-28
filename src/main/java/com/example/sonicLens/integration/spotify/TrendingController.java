package com.example.sonicLens.integration.spotify;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trending")
@RequiredArgsConstructor
public class TrendingController {

    private final SpotifySearchClient spotifySearchClient;

    /**
     * Returns trending tracks, chart playlists, and new releases in one call.
     *
     * Query params (all optional):
     *   playlistLimit  – number of chart playlists to return (default 10)
     *   trackLimit     – max trending tracks to return (default 50)
     *   releaseLimit   – number of new release albums to return (default 10)
     *
     * Trending tracks come from the first playlist in the "toplists" category
     * (typically "Top 50 - Global"). If that playlist is unavailable, an empty
     * list is returned rather than failing the whole response.
     */
    @GetMapping
    public TrendingResponse getTrending(
            @RequestParam(defaultValue = "10") int playlistLimit,
            @RequestParam(defaultValue = "50") int trackLimit,
            @RequestParam(defaultValue = "10") int releaseLimit
    ) {
        List<SpotifyPlaylistDto> playlists = spotifySearchClient.getToplistPlaylists(playlistLimit);

        List<SpotifyTrackDto> tracks = List.of();
        if (!playlists.isEmpty()) {
            String topChartId = playlists.get(0).id();
            List<SpotifyTrackDto> all = spotifySearchClient.getPlaylistTracks(topChartId);
            tracks = all.size() > trackLimit ? all.subList(0, trackLimit) : all;
        }

        List<SpotifyAlbumDto> newReleases = spotifySearchClient.getNewReleaseAlbums(releaseLimit);

        return new TrendingResponse(tracks, playlists, newReleases);
    }
}
