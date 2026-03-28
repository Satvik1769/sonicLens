package com.example.sonicLens.integration.spotify;

import java.util.List;

public record TrendingResponse(
        List<SpotifyTrackDto> trendingTracks,
        List<SpotifyPlaylistDto> trendingPlaylists,
        List<SpotifyAlbumDto> newReleases
) {}
