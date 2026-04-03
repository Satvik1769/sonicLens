package com.example.sonicLens.domain.recognition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

@RestController
@RequestMapping("/recognize")
@RequiredArgsConstructor
@Slf4j
public class RecognitionController {

    private final RecognitionService recognitionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RecognitionResult recognize(@RequestPart("file") MultipartFile file,
                                       Principal principal) throws Exception {
        RecognitionResult result =  recognitionService.recognize(file, principal.getName());
        log.error("recognize result: {}", result);
        return result;
    }
}
