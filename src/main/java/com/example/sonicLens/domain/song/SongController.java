package com.example.sonicLens.domain.song;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Song upload(@RequestPart("file") MultipartFile file,
                       @RequestPart("title") String title,
                       @RequestPart("artist") String artist) throws Exception {
        return songService.uploadSong(file, title, artist);
    }

    @GetMapping
    public List<Song> list() {
        return songService.listAll();
    }

    @GetMapping("/{id}")
    public Song get(@PathVariable Long id) {
        return songService.getById(id);
    }
}
