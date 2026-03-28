package com.example.sonicLens.integration.spotify;

public record SpotifyPlaylistDto(
        String id,
        String name,
        String description,
        String imageUrl,
        int totalTracks,
        String spotifyUrl,
        String owner
) {}
