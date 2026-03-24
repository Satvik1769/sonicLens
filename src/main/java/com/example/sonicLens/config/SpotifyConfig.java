package com.example.sonicLens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spotify")
@Data
public class SpotifyConfig {
    private String clientId;
    private String clientSecret;
}
