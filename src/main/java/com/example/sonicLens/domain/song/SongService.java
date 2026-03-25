package com.example.sonicLens.domain.song;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.integration.spotify.SpotifySearchClient;
import com.example.sonicLens.integration.spotify.SpotifyTrackDto;
import com.example.sonicLens.storage.GcsStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final FingerprintService fingerprintService;
    private final SpotifySearchClient spotifySearchClient;
    private final GcsStorageService gcsStorageService;

    @Transactional
    public Song uploadSong(MultipartFile file, String title, String artist) throws Exception {
        // 1. Read bytes once — used for both GCS upload and fingerprinting
        byte[] audioBytes = file.getBytes();
        String filename = UUID.randomUUID() + "_" +
                StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav");
        String objectName = "audio/" + filename;

        // 2. Upload to GCS
        String contentType = file.getContentType() != null ? file.getContentType() : "audio/wav";
        gcsStorageService.upload(audioBytes, objectName, contentType);

        // 3. Save song record with GCS object name as filePath
        Song song = Song.builder()
                .title(title)
                .artist(artist)
                .filePath(objectName)
                .build();
        song = songRepository.save(song);

        // 4. Generate and store fingerprints (from in-memory bytes — no GCS download needed)
        try (ByteArrayInputStream audioIn = new ByteArrayInputStream(audioBytes)) {
            fingerprintService.fingerprintSong(song, audioIn);
        }

        // 5. Enrich with Spotify metadata (best-effort)
        Optional<SpotifyTrackDto> spotifyData = spotifySearchClient.searchTrack(title, artist);
        if (spotifyData.isPresent()) {
            SpotifyTrackDto dto = spotifyData.get();
            song.setAlbum(dto.albumName());
            song.setAlbumArtUrl(dto.albumArtUrl());
            song.setSpotifyTrackId(dto.spotifyId());
            song.setSpotifyPreviewUrl(dto.previewUrl());
            song.setDurationMs(dto.durationMs());
            song = songRepository.save(song);
        }

        return song;
    }

    public List<Song> listAll() {
        return songRepository.findAll();
    }

    public Song getById(Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
    }
}
