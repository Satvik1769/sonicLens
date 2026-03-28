package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.domain.song.Song;

import java.util.List;

public record TrendingResponse(
        List<Song> trendingTracks,
        List<SpotifyPlaylistDto> trendingPlaylists,
        List<SpotifyAlbumDto> newReleases
) {}
