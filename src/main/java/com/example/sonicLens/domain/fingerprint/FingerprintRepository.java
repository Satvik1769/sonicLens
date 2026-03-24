package com.example.sonicLens.domain.fingerprint;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FingerprintRepository extends JpaRepository<Fingerprint, Long> {
    List<Fingerprint> findAllByHashIn(List<Long> hashes);
}
