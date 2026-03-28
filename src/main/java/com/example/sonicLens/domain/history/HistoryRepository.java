package com.example.sonicLens.domain.history;

import com.example.sonicLens.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HistoryRepository extends JpaRepository<RecognitionHistory, Long> {

    @Query(value = "SELECT * FROM recognition_history h  WHERE user_id = :user_id ORDER BY recognized_at DESC", nativeQuery = true)
    List<RecognitionHistory> findByUserWithDetails(@Param("user_id") Long user_id);
}
