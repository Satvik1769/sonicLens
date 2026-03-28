package com.example.sonicLens.integration.spotify;

public record SpotifyAlbumDto(
        String id,
        String name,
        String artistName,
        String albumType,
        String releaseDate,
        String imageUrl,
        int totalTracks,
        String spotifyUrl
) {}
