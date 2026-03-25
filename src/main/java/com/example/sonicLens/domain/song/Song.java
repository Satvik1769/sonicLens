package com.example.sonicLens.domain.song;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "songs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Song {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String artist;
    private String album;

    @Column(name = "spotify_track_id", length = 100)
    private String spotifyTrackId;

    @Column(name = "spotify_preview_url")
    private String spotifyPreviewUrl;

    @Column(name = "album_art_url")
    private String albumArtUrl;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "spotify_url")
    private String spotifyUrl;

    @Column(name = "track_number")
    private Integer trackNumber;

    @Column(name = "disc_number")
    private Integer discNumber;

    private Boolean explicit;

    @Column(length = 50)
    private String isrc;

    @Column(name = "album_spotify_id", length = 100)
    private String albumSpotifyId;

    @Column(name = "album_type", length = 50)
    private String albumType;

    @Column(name = "release_date", length = 20)
    private String releaseDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
