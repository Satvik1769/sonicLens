package com.example.sonicLens.domain.history;

import com.example.sonicLens.domain.song.Song;
import com.example.sonicLens.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "recognition_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecognitionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id")
    private Song song;

    private Double confidence;

    @CreationTimestamp
    @Column(name = "recognized_at", updatable = false)
    private LocalDateTime recognizedAt;
}
