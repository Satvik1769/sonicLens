package com.example.sonicLens.domain.recognition;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.domain.history.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecognitionService {


    private final FingerprintService fingerprintService;
    private final HistoryService historyService;

    public RecognitionResult recognize(MultipartFile clip, String userEmail) throws Exception {

        RecognitionResult result;
        try (var stream = clip.getInputStream()) {
            result = fingerprintService.recognize(stream);
        }
        recordHistoryAsync(userEmail, result);
        return result;
    }

    @Async
    public void recordHistoryAsync(String userEmail, RecognitionResult result) {
        try {
            historyService.record(userEmail, result);
        } catch (Exception e) {
            log.error("async history record failed for {}: {}", userEmail, e.getMessage());
        }
    }
}
