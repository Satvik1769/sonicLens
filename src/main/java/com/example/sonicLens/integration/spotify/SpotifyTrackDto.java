package com.example.sonicLens.integration.spotify;

public record SpotifyTrackDto(
        String spotifyId,
        String name,
        String artistName,
        String albumName,
        String albumArtUrl,
        String previewUrl,
        Integer durationMs
) {}
