package com.example.sonicLens.integration.spotify;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SpotifySearchClient {

    private final SpotifyAuthClient authClient;

    private final RestClient restClient = RestClient.create("https://api.spotify.com");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    public Optional<SpotifyTrackDto> searchTrack(String title, String artist) {
        try {
            String token = authClient.getAccessToken();
            String query = "track:" + title + " artist:" + artist;

            Map<String, Object> response = restClient.get()
                    .uri("/v1/search?q={q}&type=track&limit=1", query)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(MAP_TYPE);

            Map<String, Object> tracks = asMap(response.get("tracks"));
            List<Map<String, Object>> items = asList(tracks.get("items"));

            if (items == null || items.isEmpty()) return Optional.empty();

            Map<String, Object> track = items.get(0);

            List<Map<String, Object>> artists = asList(track.get("artists"));
            String artistName = (artists != null && !artists.isEmpty())
                    ? (String) artists.get(0).get("name")
                    : artist;

            Map<String, Object> album = asMap(track.get("album"));
            String albumName = (String) album.get("name");

            List<Map<String, Object>> images = asList(album.get("images"));
            String albumArtUrl = (images != null && !images.isEmpty())
                    ? (String) images.get(0).get("url")
                    : null;

            return Optional.of(new SpotifyTrackDto(
                    (String) track.get("id"),
                    (String) track.get("name"),
                    artistName,
                    albumName,
                    albumArtUrl,
                    (String) track.get("preview_url"),
                    (Integer) track.get("duration_ms")
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
