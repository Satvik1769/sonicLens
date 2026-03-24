package com.example.sonicLens.domain.song;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SongRepository extends JpaRepository<Song, Long> {
    Optional<Song> findBySpotifyTrackId(String spotifyTrackId);
}
