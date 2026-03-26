package com.example.sonicLens.domain.fingerprint;

public interface FingerprintMatch {
    Long getHash();
    Long getSongId();
    Integer getTimeOffset();
}