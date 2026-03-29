package com.example.sonicLens.domain.fingerprint;

import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Component
public class AudioDecoder {

    private static final int TARGET_SAMPLE_RATE = 11025;

    /**
     * Decodes any audio stream to mono float[] samples normalized to [-1.0, 1.0],
     * downsampled to 11025 Hz.
     *
     * Supported formats:
     *  - WAV/AIFF  — natively via AudioSystem
     *  - MP3       — via mp3spi SPI on classpath
     *  - AAC/M4A, OGG, FLAC, OPUS, WebM, etc. — via FFmpeg subprocess fallback
     *
     * FFmpeg must be installed on the server for non-WAV/MP3 formats.
     */
    public float[] decodeToMono(InputStream inputStream) throws Exception {
        BufferedInputStream buffered = new BufferedInputStream(inputStream);

        // Buffer the stream so we can retry with FFmpeg if AudioSystem rejects it
        byte[] rawBytes = buffered.readAllBytes();

        InputStream toDecodeStream;
        try {
            AudioSystem.getAudioInputStream(new ByteArrayInputStream(rawBytes));
            // AudioSystem accepted it — use the bytes directly
            toDecodeStream = new ByteArrayInputStream(rawBytes);
        } catch (UnsupportedAudioFileException e) {
            // AudioSystem can't handle this format (e.g. AAC/M4A, OGG, FLAC)
            // — convert to WAV via FFmpeg and retry
            toDecodeStream = new ByteArrayInputStream(convertToWavViaFfmpeg(rawBytes));
        }

        return decodeStream(toDecodeStream);
    }

    private float[] decodeStream(InputStream inputStream) throws Exception {
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

    /**
     * Pipes raw audio bytes through ffmpeg, returning WAV PCM bytes.
     * ffmpeg reads from stdin and writes WAV to stdout — no temp files needed.
     */
    private byte[] convertToWavViaFfmpeg(byte[] inputBytes) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg",
                "-i", "pipe:0",       // read from stdin
                "-f", "wav",          // output format
                "-acodec", "pcm_s16le",
                "-ar", "44100",
                "-ac", "2",
                "pipe:1"              // write to stdout
        )
                .redirectErrorStream(false)
                .start();

        // Write input to ffmpeg stdin in a separate thread to avoid blocking
        Thread writer = new Thread(() -> {
            try (var out = process.getOutputStream()) {
                out.write(inputBytes);
            } catch (Exception ignored) {}
        });
        writer.start();

        byte[] wavBytes = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        writer.join();

        if (exitCode != 0 || wavBytes.length == 0) {
            throw new UnsupportedAudioFileException(
                    "FFmpeg conversion failed (exit " + exitCode + "). " +
                    "Ensure ffmpeg is installed: apt install ffmpeg / brew install ffmpeg");
        }
        return wavBytes;
    }
}
