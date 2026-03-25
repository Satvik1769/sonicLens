package com.example.sonicLens.integration.spotify;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SpotifySearchClient {

    private final SpotifyAuthClient authClient;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // -------------------------------------------------------------------------
    // Search by title + artist (top 1 result) — used internally after upload
    // -------------------------------------------------------------------------

    public Optional<SpotifyTrackDto> searchTrack(String title, String artist) {
        List<SpotifyTrackDto> results = searchTracks("track:" + title + " artist:" + artist, 1);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -------------------------------------------------------------------------
    // Search by free-text query — returns up to `limit` results for user to pick
    // -------------------------------------------------------------------------

    public List<SpotifyTrackDto> searchTracks(String query, int limit) {
        try {
            HttpEntity<Void> request = authHeaders();

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://api.spotify.com/v1/search?q={q}&type=track&limit={limit}",
                    HttpMethod.GET,
                    request,
                    MAP_TYPE,
                    query, limit
            );

            Map<String, Object> body = response.getBody();
            if (body == null) return List.of();

            Map<String, Object> tracks = asMap(body.get("tracks"));
            List<Map<String, Object>> items = asList(tracks.get("items"));
            if (items == null) return List.of();

            List<SpotifyTrackDto> result = new ArrayList<>();
            for (Map<String, Object> track : items) {
                toDto(track).ifPresent(result::add);
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Fetch a single track by Spotify track ID
    // -------------------------------------------------------------------------

    public Optional<SpotifyTrackDto> getTrack(String trackId) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://api.spotify.com/v1/tracks/{id}",
                    HttpMethod.GET,
                    authHeaders(),
                    MAP_TYPE,
                    trackId
            );
            Map<String, Object> body = response.getBody();
            return body == null ? Optional.empty() : toDto(body);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Fetch all tracks of a Spotify album (handles pagination)
    // -------------------------------------------------------------------------

    public List<SpotifyTrackDto> getAlbumTracks(String albumId) {
        try {
            // Get album metadata (name + art) first
            ResponseEntity<Map<String, Object>> albumResponse = restTemplate.exchange(
                    "https://api.spotify.com/v1/albums/{id}",
                    HttpMethod.GET,
                    authHeaders(),
                    MAP_TYPE,
                    albumId
            );
            Map<String, Object> albumBody = albumResponse.getBody();
            if (albumBody == null) return List.of();

            String albumName      = (String) albumBody.get("name");
            String albumType      = (String) albumBody.get("album_type");
            String releaseDate    = (String) albumBody.get("release_date");
            List<Map<String, Object>> albumImages = asList(albumBody.get("images"));
            String albumArtUrl = (albumImages != null && !albumImages.isEmpty())
                    ? (String) albumImages.get(0).get("url") : null;

            // Page through tracks (max 50 per request)
            List<SpotifyTrackDto> allTracks = new ArrayList<>();
            int offset = 0;
            final int limit = 50;

            while (true) {
                ResponseEntity<Map<String, Object>> tracksResponse = restTemplate.exchange(
                        "https://api.spotify.com/v1/albums/{id}/tracks?limit={limit}&offset={offset}",
                        HttpMethod.GET,
                        authHeaders(),
                        MAP_TYPE,
                        albumId, limit, offset
                );
                Map<String, Object> tracksBody = tracksResponse.getBody();
                if (tracksBody == null) break;

                List<Map<String, Object>> items = asList(tracksBody.get("items"));
                if (items == null || items.isEmpty()) break;

                for (Map<String, Object> track : items) {
                    try {
                        List<Map<String, Object>> artists = asList(track.get("artists"));
                        String artistName = (artists != null && !artists.isEmpty())
                                ? (String) artists.get(0).get("name") : null;

                        Map<String, Object> externalUrls = asMap(track.get("external_urls"));
                        String spotifyUrl = externalUrls != null ? (String) externalUrls.get("spotify") : null;

                        allTracks.add(new SpotifyTrackDto(
                                (String)  track.get("id"),
                                (String)  track.get("name"),
                                artistName,
                                albumName,
                                albumArtUrl,
                                (String)  track.get("preview_url"),
                                (Integer) track.get("duration_ms"),
                                spotifyUrl,
                                (Integer) track.get("track_number"),
                                (Integer) track.get("disc_number"),
                                (Boolean) track.get("explicit"),
                                null,          // isrc not available in simplified album track objects
                                albumId,
                                albumType,
                                releaseDate
                        ));
                    } catch (Exception ignored) {}
                }

                offset += items.size();
                Object total = tracksBody.get("total");
                if (total == null || offset >= (Integer) total) break;
            }

            return allTracks;
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Parse a Spotify track/album URL or URI into (type, id)
    // Supports:
    //   https://open.spotify.com/track/{id}
    //   https://open.spotify.com/album/{id}
    //   spotify:track:{id}
    //   spotify:album:{id}
    // -------------------------------------------------------------------------

    public record SpotifyUrlParsed(String type, String id) {}

    public SpotifyUrlParsed parseSpotifyUrl(String url) {
        String trimmed = url.trim();

        // Spotify URI: spotify:track:xxx, spotify:album:xxx, spotify:playlist:xxx
        if (trimmed.startsWith("spotify:")) {
            String[] parts = trimmed.split(":");
            if (parts.length >= 3 && (parts[1].equals("track") || parts[1].equals("album") || parts[1].equals("playlist"))) {
                return new SpotifyUrlParsed(parts[1], parts[2]);
            }
        }

        // HTTP URL: https://open.spotify.com/track/{id}?si=...
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            String[] segments = uri.getPath().split("/");
            // segments: ["", "track"/"album"/"playlist", "{id}"]
            if (segments.length >= 3) {
                String type = segments[1];
                String id   = segments[2];
                if ((type.equals("track") || type.equals("album") || type.equals("playlist"))
                        && !id.isBlank()) {
                    return new SpotifyUrlParsed(type, id);
                }
            }
        } catch (Exception ignored) {}

        throw new IllegalArgumentException(
                "Invalid Spotify URL. Provide a track, album, or playlist link, e.g. " +
                "https://open.spotify.com/track/{id}, https://open.spotify.com/album/{id}, " +
                "or https://open.spotify.com/playlist/{id}");
    }

    // -------------------------------------------------------------------------
    // Fetch all tracks from a Spotify playlist (handles pagination, 100/page)
    // -------------------------------------------------------------------------

    public List<SpotifyTrackDto> getPlaylistTracks(String playlistId) {
        try {
            List<SpotifyTrackDto> allTracks = new ArrayList<>();
            int offset = 0;
            final int limit = 100;

            while (true) {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        "https://api.spotify.com/v1/playlists/{id}/tracks?limit={limit}&offset={offset}",
                        HttpMethod.GET,
                        authHeaders(),
                        MAP_TYPE,
                        playlistId, limit, offset
                );
                Map<String, Object> body = response.getBody();
                if (body == null) break;

                List<Map<String, Object>> items = asList(body.get("items"));
                if (items == null || items.isEmpty()) break;

                for (Map<String, Object> item : items) {
                    // Playlist items wrap the real track under "track" key
                    Map<String, Object> track = asMap(item.get("track"));
                    if (track == null || track.get("id") == null) continue; // local files have no id
                    toDto(track).ifPresent(allTracks::add);
                }

                offset += items.size();
                if (body.get("next") == null) break;
            }

            return allTracks;
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Returns playlist IDs from Spotify's featured playlists
    // -------------------------------------------------------------------------

    public List<String> getFeaturedPlaylistIds(int limit) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://api.spotify.com/v1/browse/featured-playlists?limit={limit}",
                    HttpMethod.GET,
                    authHeaders(),
                    MAP_TYPE,
                    limit
            );
            Map<String, Object> body = response.getBody();
            if (body == null) return List.of();

            Map<String, Object> playlists = asMap(body.get("playlists"));
            List<Map<String, Object>> items = playlists != null ? asList(playlists.get("items")) : null;
            if (items == null) return List.of();

            return items.stream()
                    .map(p -> (String) p.get("id"))
                    .filter(id -> id != null)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Returns album IDs from Spotify's new releases
    // -------------------------------------------------------------------------

    public List<String> getNewReleaseAlbumIds(int limit) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://api.spotify.com/v1/browse/new-releases?limit={limit}",
                    HttpMethod.GET,
                    authHeaders(),
                    MAP_TYPE,
                    limit
            );
            Map<String, Object> body = response.getBody();
            if (body == null) return List.of();

            Map<String, Object> albums = asMap(body.get("albums"));
            List<Map<String, Object>> items = albums != null ? asList(albums.get("items")) : null;
            if (items == null) return List.of();

            return items.stream()
                    .map(a -> (String) a.get("id"))
                    .filter(id -> id != null)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Download the 30-second preview MP3 from its URL (no auth needed)
    // -------------------------------------------------------------------------

    public byte[] downloadPreview(String previewUrl) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                previewUrl,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                byte[].class
        );
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Empty preview download from: " + previewUrl);
        }
        return body;
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private HttpEntity<Void> authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authClient.getAccessToken());
        return new HttpEntity<>(headers);
    }

    private Optional<SpotifyTrackDto> toDto(Map<String, Object> track) {
        try {
            // Artists
            List<Map<String, Object>> artists = asList(track.get("artists"));
            String artistName = (artists != null && !artists.isEmpty())
                    ? (String) artists.get(0).get("name") : null;

            // Album
            Map<String, Object> album = asMap(track.get("album"));
            String albumName       = album != null ? (String)  album.get("name")         : null;
            String albumSpotifyId  = album != null ? (String)  album.get("id")           : null;
            String albumType       = album != null ? (String)  album.get("album_type")   : null;
            String releaseDate     = album != null ? (String)  album.get("release_date") : null;

            List<Map<String, Object>> images = album != null ? asList(album.get("images")) : null;
            String albumArtUrl = (images != null && !images.isEmpty())
                    ? (String) images.get(0).get("url") : null;

            // Track external URL
            Map<String, Object> externalUrls = asMap(track.get("external_urls"));
            String spotifyUrl = externalUrls != null ? (String) externalUrls.get("spotify") : null;

            // ISRC
            Map<String, Object> externalIds = asMap(track.get("external_ids"));
            String isrc = externalIds != null ? (String) externalIds.get("isrc") : null;

            return Optional.of(new SpotifyTrackDto(
                    (String)  track.get("id"),
                    (String)  track.get("name"),
                    artistName,
                    albumName,
                    albumArtUrl,
                    (String)  track.get("preview_url"),
                    (Integer) track.get("duration_ms"),
                    spotifyUrl,
                    (Integer) track.get("track_number"),
                    (Integer) track.get("disc_number"),
                    (Boolean) track.get("explicit"),
                    isrc,
                    albumSpotifyId,
                    albumType,
                    releaseDate
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
