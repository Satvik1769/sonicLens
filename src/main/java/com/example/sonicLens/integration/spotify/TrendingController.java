package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.domain.song.SaveResult;
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
     * Returns trending tracks (Song DB entities), chart playlists, and new releases.
     *
     * All tracks — both from "top hits" search and from each playlist name search —
     * are saved to the songs table immediately and fingerprinted in one background task.
     *
     * Query params (all optional):
     *   playlistLimit  – chart playlists to return (default 10)
     *   trackLimit     – tracks per source (default 10)
     *   releaseLimit   – new release albums to return (default 10)
     */
    @GetMapping
    public TrendingResponse getTrending(
            @RequestParam(defaultValue = "10") int playlistLimit,
            @RequestParam(defaultValue = "10") int trackLimit,
            @RequestParam(defaultValue = "10") int releaseLimit
    ) {
        List<SpotifyPlaylistDto> playlists = spotifySearchClient.getToplistPlaylists(playlistLimit);
        List<SpotifyAlbumDto> newReleases = spotifySearchClient.getNewReleaseAlbums(releaseLimit);

        // Collect all track DTOs: top hits + tracks from each playlist (searched by name)
        List<SpotifyTrackDto> allDtos = new ArrayList<>(
                spotifySearchClient.searchTracks("top hits", trackLimit));

        for (SpotifyPlaylistDto playlist : playlists) {
            allDtos.addAll(spotifySearchClient.searchTracks(playlist.name(), trackLimit));
        }

        // Save metadata for all tracks immediately, deduplicated by spotifyTrackId
        List<Song> newSongs = new ArrayList<>();
        List<Song> allSongs = new ArrayList<>();
        for (SpotifyTrackDto dto : allDtos) {
            if (dto.spotifyId() == null) continue;
            SaveResult result = songService.saveMetadataOnly(dto);
            allSongs.add(result.song());
            if (result.isNew()) newSongs.add(result.song());
        }

        // Single async task — fingerprints sequentially so librespot isn't flooded
        if (!newSongs.isEmpty()) {
            songService.fingerprintInBackground(newSongs);
        }

        return new TrendingResponse(allSongs, playlists, newReleases);
    }
}
