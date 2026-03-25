package com.example.sonicLens.integration.spotify;

import com.example.sonicLens.config.SpotifyConfig;
import com.example.sonicLens.domain.user.User;
import com.example.sonicLens.domain.user.UserRepository;
import com.example.sonicLens.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SpotifyOAuthController {

    private final SpotifyConfig config;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // Scopes needed for Web Playback SDK + user info
    private static final String SCOPES =
            "streaming user-read-email user-read-private user-read-playback-state user-modify-playback-state";

    /**
     * Step 1 — Redirect user to Spotify login.
     * Frontend calls: GET /auth/spotify  (with JWT header)
     * Returns the Spotify authorization URL so the frontend can redirect.
     */
    @GetMapping("/auth/spotify")
    public Map<String, String> getSpotifyAuthUrl(Principal principal) {
        String authUrl = "https://accounts.spotify.com/authorize" +
                "?client_id=" + config.getClientId() +
                "&response_type=code" +
                "&redirect_uri=" + config.getRedirectUri() +
                "&scope=" + SCOPES.replace(" ", "%20") +
                "&state=" + jwtUtil.generateToken(principal.getName());

        return Map.of("authUrl", authUrl);
    }

    /**
     * Step 2 — Spotify redirects here with ?code=xxx&state=jwtToken
     * Exchanges the code for access + refresh tokens and saves them to the user.
     * Redirects the browser to the frontend after success.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code,
                                         @RequestParam String state) {
        // Validate state (JWT) to identify which user authorized
        if (!jwtUtil.isTokenValid(state)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid state token");
        }
        String email = jwtUtil.extractEmail(state);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Exchange code for tokens
        String credentials = Base64.getEncoder().encodeToString(
                (config.getClientId() + ":" + config.getClientSecret()).getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + credentials);

        String body = "grant_type=authorization_code"
                + "&code=" + code
                + "&redirect_uri=" + config.getRedirectUri();

        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://accounts.spotify.com/api/token",
                HttpMethod.POST,
                request,
                MAP_TYPE
        );

        Map<String, Object> tokens = response.getBody();
        int expiresIn = (Integer) tokens.get("expires_in");

        user.setSpotifyAccessToken((String) tokens.get("access_token"));
        user.setSpotifyRefreshToken((String) tokens.get("refresh_token"));
        user.setSpotifyTokenExpiry(Instant.now().plusSeconds(expiresIn));
        userRepository.save(user);

        // Redirect to frontend (adjust URL for your frontend)
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("http://127.0.0.1:3000?spotify=connected"))
                .build();
    }

    /**
     * Returns a fresh Spotify access token for the authenticated user.
     * Frontend calls this before initializing the Web Playback SDK.
     */
    @GetMapping("/auth/spotify/token")
    public Map<String, Object> getToken(Principal principal) {
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (user.getSpotifyAccessToken() == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Spotify not connected. Call /auth/spotify first.");
        }

        // Refresh token if expired (or expires within 60s)
        if (user.getSpotifyTokenExpiry() == null ||
                Instant.now().isAfter(user.getSpotifyTokenExpiry().minusSeconds(60))) {
            user = refreshToken(user);
        }

        return Map.of(
                "accessToken", user.getSpotifyAccessToken(),
                "expiresAt", user.getSpotifyTokenExpiry().toEpochMilli()
        );
    }

    private User refreshToken(User user) {
        String credentials = Base64.getEncoder().encodeToString(
                (config.getClientId() + ":" + config.getClientSecret()).getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + credentials);

        String body = "grant_type=refresh_token&refresh_token=" + user.getSpotifyRefreshToken();
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://accounts.spotify.com/api/token",
                HttpMethod.POST,
                request,
                MAP_TYPE
        );

        Map<String, Object> tokens = response.getBody();
        int expiresIn = (Integer) tokens.get("expires_in");

        user.setSpotifyAccessToken((String) tokens.get("access_token"));
        user.setSpotifyTokenExpiry(Instant.now().plusSeconds(expiresIn));
        // refresh_token may or may not be rotated — update if present
        if (tokens.get("refresh_token") != null) {
            user.setSpotifyRefreshToken((String) tokens.get("refresh_token"));
        }
        return userRepository.save(user);
    }
}
