package com.example.sonicLens.domain.recognition;

import com.example.sonicLens.domain.song.Song;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecognitionResult {

    private boolean matched;
    private Song song;
    private double confidence;

    public static RecognitionResult noMatch() {
        return RecognitionResult.builder()
                .matched(false)
                .confidence(0.0)
                .build();
    }

    public static RecognitionResult of(Song song, double confidence) {
        return RecognitionResult.builder()
                .matched(true)
                .song(song)
                .confidence(confidence)
                .build();
    }
}
