package com.example.sonicLens.scheduler;

import com.example.sonicLens.domain.song.Song;
import com.example.sonicLens.domain.song.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpotifyScheduler {

    private final SongService songService;

    // Rotates through one query per hourly run
    private final AtomicInteger queryIndex = new AtomicInteger(0);

    // Per-job locks to prevent overlapping runs
    private final ReentrantLock newReleasesLock     = new ReentrantLock();
    private final ReentrantLock featuredLock        = new ReentrantLock();
    private final ReentrantLock genreQueryLock      = new ReentrantLock();

    /** Random delay between 4–8 seconds between tracks to avoid rate-limit patterns. */
    private static long randomDelay() {
        return ThreadLocalRandom.current().nextLong(4_000, 8_001);
    }

    private static final List<String> SEARCH_QUERIES = List.of(
            // Bollywood / Hindi
            "bollywood hits", "hindi songs", "hindi pop", "bollywood 2024",
            "arijit singh", "shreya ghoshal", "atif aslam", "jubin nautiyal",
            "bollywood romantic", "hindi rap", "punjabi hits", "haryanvi songs",

            // Tollywood / Telugu
            "telugu hits", "tollywood songs", "telugu 2024", "telugu melody",
            "ss thaman", "devi sri prasad", "telugu romantic songs",

            // Tamil / Kollywood
            "tamil hits", "kollywood songs", "tamil 2024", "tamil melody",
            "anirudh ravichander", "harris jayaraj", "ar rahman tamil",
            "tamil kuthu songs",

            // South Indian (Kannada, Malayalam)
            "kannada hits", "kannada songs 2024",
            "malayalam hits", "malayalam melody",

            // English
            "top pop hits", "billboard hot 100", "english hits 2024",
            "hip hop hits", "rnb hits", "indie pop", "rock hits",
            "edm hits", "latin pop hits", "country hits",

            // Japanese
            "j-pop hits", "anime songs", "japanese pop 2024",
            "jpop top hits", "anime ost",

            // Korean
            "kpop hits", "kpop 2024", "bts songs", "blackpink songs",

            // Other world music
            "arabic pop hits", "spanish hits", "afrobeats hits",
            "reggaeton hits", "turkish pop hits"
    );

    /**
     * Daily at 2 AM — pull new releases from Spotify.
     * Skipped if a previous run is still in progress.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void seedNewReleases() {
        if (!newReleasesLock.tryLock()) {
            log.error("[scheduler] new-releases already running, skipping this trigger");
            return;
        }
        try {
            log.error("[scheduler] Starting daily new releases seed");
            List<Song> songs = songService.seedFromSpotify(
                    SongService.SeedStrategy.NEW_RELEASES, 20, null, randomDelay());
            log.error("[scheduler] New releases: added {} songs", songs.size());
        } catch (Exception e) {
            log.error("[scheduler] New releases seed failed: {}", e.getMessage(), e);
        } finally {
            newReleasesLock.unlock();
        }
    }

    /**
     * Every 6 hours — refresh featured / trending playlists.
     * Skipped if a previous run is still in progress.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void seedFeaturedPlaylists() {
        if (!featuredLock.tryLock()) {
            log.error("[scheduler] featured-playlists already running, skipping this trigger");
            return;
        }
        try {
            log.error("[scheduler] Starting featured playlists seed");
            List<Song> songs = songService.seedFromSpotify(
                    SongService.SeedStrategy.FEATURED_PLAYLISTS, 30, null, randomDelay());
            log.error("[scheduler] Featured playlists: added {} songs", songs.size());
        } catch (Exception e) {
            log.error("[scheduler] Featured playlists seed failed: {}", e.getMessage(), e);
        } finally {
            featuredLock.unlock();
        }
    }

    /**
     * Every hour at :30 — run next genre/language query from the rotating list.
     * Cycles through all queries (~42 hours) then repeats.
     * Skipped if a previous run is still in progress.
     */
    @Scheduled(cron = "0 30 * * * *")
    public void seedGenreQuery() {
        if (!genreQueryLock.tryLock()) {
            log.error("[scheduler] genre-query already running, skipping this trigger");
            return;
        }
        int idx = queryIndex.getAndUpdate(i -> (i + 1) % SEARCH_QUERIES.size());
        String query = SEARCH_QUERIES.get(idx);
        try {
            log.error("[scheduler] Genre query [{}/{}]: '{}'", idx + 1, SEARCH_QUERIES.size(), query);
            List<Song> songs = songService.seedFromSpotify(
                    SongService.SeedStrategy.SEARCH, 20, query, randomDelay());
            log.error("[scheduler] Genre query '{}': added {} songs", query, songs.size());
        } catch (Exception e) {
            log.error("[scheduler] Genre query '{}' failed: {}", query, e.getMessage(), e);
        } finally {
            genreQueryLock.unlock();
        }
    }
}