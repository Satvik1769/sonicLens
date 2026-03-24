package com.example.sonicLens.domain.fingerprint;

import com.example.sonicLens.domain.song.Song;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "fingerprints",
       indexes = @Index(name = "idx_fingerprints_hash", columnList = "hash"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long hash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id")
    private Song song;

    @Column(name = "time_offset", nullable = false)
    private Integer timeOffset;
}
