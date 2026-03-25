package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.config.SpotifyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SpotifyAuthClient {

    private final SpotifyConfig config;

    private final RestTemplate restTemplate = new RestTemplate();

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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + credentials);

        HttpEntity<String> request = new HttpEntity<>("grant_type=client_credentials", headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://accounts.spotify.com/api/token",
                HttpMethod.POST,
                request,
                MAP_TYPE
        );

        Map<String, Object> body = response.getBody();
        cachedToken = (String) body.get("access_token");
        int expiresIn = (Integer) body.get("expires_in");
        tokenExpiry = Instant.now().plusSeconds(expiresIn);

        return cachedToken;
    }
}
