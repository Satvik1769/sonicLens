package com.example.sonicLens.domain.history;

import com.example.sonicLens.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HistoryRepository extends JpaRepository<RecognitionHistory, Long> {

    @Query(value = "SELECT * FROM recognition_history h LEFT JOIN fetch h.song WHERE h.user = :user ORDER BY h.recognizedAt DESC", nativeQuery = true)
    List<RecognitionHistory> findByUserWithDetails(@Param("user") User user);
}
