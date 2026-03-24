package com.example.sonicLens.domain.history;

import com.example.sonicLens.domain.recognition.RecognitionResult;
import com.example.sonicLens.domain.user.User;
import com.example.sonicLens.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryRepository historyRepository;
    private final UserRepository userRepository;

    @Transactional
    public void record(String email, RecognitionResult result) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        RecognitionHistory entry = RecognitionHistory.builder()
                .user(user)
                .song(result.isMatched() ? result.getSong() : null)
                .confidence(result.getConfidence())
                .build();
        historyRepository.save(entry);
    }

    public List<RecognitionHistory> getHistory(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return historyRepository.findByUserWithDetails(user);
    }

    @Transactional
    public void delete(Long id, String email) {
        RecognitionHistory entry = historyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "History entry not found"));
        if (!entry.getUser().getEmail().equals(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your history entry");
        }
        historyRepository.delete(entry);
    }
}
