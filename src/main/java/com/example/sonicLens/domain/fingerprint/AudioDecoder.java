package com.example.sonicLens.domain.fingerprint;

import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;

@Component
public class AudioDecoder {

    private static final int TARGET_SAMPLE_RATE = 11025;

    /**
     * Decodes any AudioSystem-supported audio stream to mono float[] samples
     * normalized to [-1.0, 1.0], downsampled to 11025 Hz.
     */
    public float[] decodeToMono(InputStream inputStream) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(
                new BufferedInputStream(inputStream));

        // Compressed formats (e.g. MP3 via mp3spi) report MPEG encoding.
        // AudioSystem cannot convert MPEG → target PCM in one step, so decode
        // to PCM at the source's native rate/channels first.
        AudioFormat fmt = ais.getFormat();
        if (!fmt.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                && !fmt.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            float rate     = fmt.getSampleRate() != AudioSystem.NOT_SPECIFIED ? fmt.getSampleRate() : 44100f;
            int   channels = fmt.getChannels()   != AudioSystem.NOT_SPECIFIED ? fmt.getChannels()   : 2;
            AudioFormat pcmNative = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false);
            ais = AudioSystem.getAudioInputStream(pcmNative, ais);
        }

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                TARGET_SAMPLE_RATE,
                16,
                1,
                2,
                TARGET_SAMPLE_RATE,
                false
        );

        AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, ais);
        byte[] bytes = converted.readAllBytes();
        converted.close();

        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            samples[i] = s / 32768.0f;
        }
        return samples;
    }
}
