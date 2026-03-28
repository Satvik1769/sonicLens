package com.example.sonicLens.domain.fingerprint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FingerprintRepository extends JpaRepository<Fingerprint, Long> {

    @Query(value = "SELECT f.hash as hash, f.song_id as songId, f.time_offset as timeOffset " +
                   "FROM fingerprints f WHERE f.hash IN :hashes",
           nativeQuery = true)
    List<FingerprintMatch> findMatchesByHashIn(@Param("hashes") List<Long> hashes);

    @Query(value = "DELETE FROM fingerprints WHERE song_id = :songId", nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void deleteBySongId(@Param("songId") Long songId);
}