package com.example.sonicLens.domain.song;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.integration.spotify.SpotifySearchClient;
import com.example.sonicLens.integration.spotify.SpotifyTrackDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final FingerprintService fingerprintService;
    private final SpotifySearchClient spotifySearchClient;

    @Value("${app.audio-storage-path:./audio-files}")
    private String storagePath;

    @Transactional
    public Song uploadSong(MultipartFile file, String title, String artist) throws Exception {
        // 1. Save audio file to disk
        Path dir = Path.of(storagePath);
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + "_" +
                StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav");
        Path dest = dir.resolve(filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

        // 2. Save song record
        Song song = Song.builder()
                .title(title)
                .artist(artist)
                .filePath(dest.toString())
                .build();
        song = songRepository.save(song);

        // 3. Generate and store fingerprints
        try (InputStream audioIn = Files.newInputStream(dest)) {
            fingerprintService.fingerprintSong(song, audioIn);
        }

        // 4. Enrich with Spotify metadata (best-effort)
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
