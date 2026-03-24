package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.config.SpotifyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SpotifyAuthClient {

    private final SpotifyConfig config;

    private final RestClient restClient = RestClient.create("https://accounts.spotify.com");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }

        String credentials = Base64.getEncoder().encodeToString(
                (config.getClientId() + ":" + config.getClientSecret()).getBytes());

        Map<String, Object> response = restClient.post()
                .uri("/api/token")
                .header("Authorization", "Basic " + credentials)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(MAP_TYPE);

        cachedToken = (String) response.get("access_token");
        int expiresIn = (Integer) response.get("expires_in");
        tokenExpiry = Instant.now().plusSeconds(expiresIn);

        return cachedToken;
    }
}
