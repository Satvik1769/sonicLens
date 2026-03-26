package com.example.sonicLens.domain.fingerprint;

import com.example.sonicLens.domain.recognition.RecognitionResult;
import com.example.sonicLens.domain.song.Song;
import com.example.sonicLens.domain.song.SongRepository;
import lombok.RequiredArgsConstructor;
import org.jtransforms.fft.DoubleFFT_1D;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FingerprintService {

    private final AudioDecoder audioDecoder;
    private final FingerprintRepository fingerprintRepository;
    private final SongRepository songRepository;

    @PersistenceContext
    private EntityManager em;

    private static final int FLUSH_BATCH = 500;

    // FFT parameters
    private static final int SAMPLE_RATE = 11025;
    private static final int FFT_SIZE    = 4096;
    private static final int HOP_SIZE    = FFT_SIZE / 2;

    // Frequency band edges in Hz (6 bands)
    private static final int[] BAND_EDGES_HZ = {40, 80, 160, 512, 1024, 2048, 4096};

    // Peak detection threshold — filters near-silent frames
    private static final double PEAK_THRESHOLD = 10.0;

    // Target zone for combinatorial hashing
    private static final int TARGET_ZONE_MIN   = 1;
    private static final int TARGET_ZONE_MAX   = 100;
    private static final int TARGET_ZONE_DFREQ = 200;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Transactional
    public void fingerprintSong(Song song, InputStream audioStream) throws Exception {
        float[] samples = audioDecoder.decodeToMono(audioStream);
        double[][] spec  = buildSpectrogram(samples);
        List<long[]> peaks  = detectPeaks(spec);
        List<long[]> hashes = generateHashes(peaks);

        int count = 0;
        for (long[] h : hashes) {
            Fingerprint fp = Fingerprint.builder()
                    .hash(h[0])
                    .song(song)
                    .timeOffset((int) h[1])
                    .build();
            em.persist(fp);
            if (++count % FLUSH_BATCH == 0) {
                em.flush();
                em.clear();
            }
        }
        if (count % FLUSH_BATCH != 0) {
            em.flush();
        }
    }

    public RecognitionResult recognize(InputStream clipStream) throws Exception {
        float[] samples  = audioDecoder.decodeToMono(clipStream);
        double[][] spec  = buildSpectrogram(samples);
        List<long[]> peaks     = detectPeaks(spec);
        List<long[]> clipHashes = generateHashes(peaks);

        if (clipHashes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No fingerprints extracted from audio clip");
        }

        List<Long> hashValues = clipHashes.stream()
                .map(h -> h[0])
                .distinct()
                .collect(Collectors.toList());

        List<Fingerprint> matches = fingerprintRepository.findAllByHashIn(hashValues);
        if (matches.isEmpty()) return RecognitionResult.noMatch();

        // Build clip hash → anchor frame lookup (first occurrence wins)
        Map<Long, Integer> clipHashToFrame = new HashMap<>();
        for (long[] h : clipHashes) {
            clipHashToFrame.putIfAbsent(h[0], (int) h[1]);
        }

        // Vote by time-alignment delta: delta = clipFrame - dbTimeOffset
        // All correct matches from the same song share the same constant delta.
        Map<String, Integer> votes = new HashMap<>();
        for (Fingerprint fp : matches) {
            Integer clipFrame = clipHashToFrame.get(fp.getHash());
            if (clipFrame == null) continue;
            int delta = clipFrame - fp.getTimeOffset();
            String key = fp.getSong().getId() + ":" + delta;
            votes.merge(key, 1, Integer::sum);
        }

        // Find winner
        Optional<Map.Entry<String, Integer>> winner = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue());

        if (winner.isEmpty()) return RecognitionResult.noMatch();

        String[] parts    = winner.get().getKey().split(":");
        Long songId       = Long.parseLong(parts[0]);
        int winnerVotes   = winner.get().getValue();
        double confidence = (double) winnerVotes / clipHashes.size();

        if (confidence < 0.05) return RecognitionResult.noMatch();

        Song song = songRepository.findById(songId).orElse(null);
        return RecognitionResult.of(song, confidence);
    }

    // -------------------------------------------------------------------------
    // Step 1 — Spectrogram via sliding-window FFT
    // -------------------------------------------------------------------------

    double[][] buildSpectrogram(float[] samples) {
        DoubleFFT_1D fft = new DoubleFFT_1D((long) FFT_SIZE);
        double[] window  = buildHannWindow(FFT_SIZE);

        int numFrames   = Math.max(0, (samples.length - FFT_SIZE) / HOP_SIZE + 1);
        double[][] spec = new double[numFrames][FFT_SIZE / 2];

        for (int frame = 0; frame < numFrames; frame++) {
            int offset  = frame * HOP_SIZE;
            double[] buf = new double[FFT_SIZE];

            for (int i = 0; i < FFT_SIZE; i++) {
                buf[i] = samples[offset + i] * window[i];
            }

            fft.realForward(buf);

            // JTransforms realForward layout: [re0, re1, im1, re2, im2, ..., re(N/2)]
            for (int k = 1; k < FFT_SIZE / 2; k++) {
                double re = buf[2 * k];
                double im = buf[2 * k + 1];
                spec[frame][k] = Math.sqrt(re * re + im * im);
            }
        }
        return spec;
    }

    private double[] buildHannWindow(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return w;
    }

    // -------------------------------------------------------------------------
    // Step 2 — Peak detection in log-scaled frequency bands
    // -------------------------------------------------------------------------

    List<long[]> detectPeaks(double[][] spectrogram) {
        int[][] bands  = computeBandBinEdges();
        List<long[]> peaks = new ArrayList<>();

        for (int frame = 0; frame < spectrogram.length; frame++) {
            for (int[] band : bands) {
                int lo = band[0];
                int hi = Math.min(band[1], spectrogram[frame].length);

                double maxMag = 0;
                int maxBin  = -1;
                for (int bin = lo; bin < hi; bin++) {
                    if (spectrogram[frame][bin] > maxMag) {
                        maxMag = spectrogram[frame][bin];
                        maxBin = bin;
                    }
                }
                if (maxBin != -1 && maxMag > PEAK_THRESHOLD) {
                    peaks.add(new long[]{frame, maxBin});
                }
            }
        }
        return peaks;
    }

    private int[][] computeBandBinEdges() {
        int[][] edges = new int[BAND_EDGES_HZ.length - 1][2];
        for (int i = 0; i < edges.length; i++) {
            edges[i][0] = hzToBin(BAND_EDGES_HZ[i]);
            edges[i][1] = hzToBin(BAND_EDGES_HZ[i + 1]);
        }
        return edges;
    }

    private int hzToBin(int hz) {
        return (int) Math.round((double) hz * FFT_SIZE / SAMPLE_RATE);
    }

    // -------------------------------------------------------------------------
    // Step 3 — Combinatorial hashing (anchor + target zone pairing)
    // -------------------------------------------------------------------------

    List<long[]> generateHashes(List<long[]> peaks) {
        List<long[]> result = new ArrayList<>();
        for (int i = 0; i < peaks.size(); i++) {
            long anchorFrame = peaks.get(i)[0];
            long anchorBin   = peaks.get(i)[1];

            for (int j = i + 1; j < peaks.size(); j++) {
                long targetFrame = peaks.get(j)[0];
                long targetBin   = peaks.get(j)[1];

                long dt = targetFrame - anchorFrame;
                if (dt < TARGET_ZONE_MIN) continue;
                if (dt > TARGET_ZONE_MAX) break;   // peaks are time-sorted

                long df = Math.abs(targetBin - anchorBin);
                if (df > TARGET_ZONE_DFREQ) continue;

                long hash = packHash(anchorBin, targetBin, dt);
                result.add(new long[]{hash, anchorFrame});
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Step 4 — Hash packing into 64-bit long
    //   bits [63..52] = anchorBin  (12 bits)
    //   bits [51..40] = targetBin  (12 bits)
    //   bits [39..0 ] = timeDelta  (40 bits)
    // -------------------------------------------------------------------------

    private long packHash(long anchorBin, long targetBin, long timeDelta) {
        return (anchorBin << 52) | (targetBin << 40) | timeDelta;
    }
}
