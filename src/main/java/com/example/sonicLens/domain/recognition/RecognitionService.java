package com.example.sonicLens.domain.recognition;

import com.example.sonicLens.domain.fingerprint.FingerprintService;
import com.example.sonicLens.domain.history.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
        historyService.record(userEmail, result);
        return result;
    }
}
