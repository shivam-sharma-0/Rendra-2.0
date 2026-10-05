package com.code.pal.project.supabase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The few Supabase calls the backend makes: checking a project URL + anon key, checking a personal
 * access token, and running an approved migration through the Management API. Only URLs matching the
 * configured project pattern are ever requested, so a user-supplied URL cannot point the backend at
 * internal hosts.
 */
@Component
public class SupabaseClient {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_ERROR = 1_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper;
    private final String managementApi;

    public SupabaseClient(ObjectMapper objectMapper, @Value("${app.supabase.api-url:https://api.supabase.com}") String managementApi) {
        this.objectMapper = objectMapper;
        this.managementApi = managementApi.replaceAll("/+$", "");
    }

    /** @throws SupabaseException when the URL or the anon key is not accepted by Supabase */
    public void verifyAnonKey(String projectUrl, String anonKey) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(projectUrl + "/auth/v1/settings"))
                .header("apikey", anonKey).header("Authorization", "Bearer " + anonKey).timeout(CHECK_TIMEOUT).GET().build());
        if (response.statusCode() != 200) {
            throw new SupabaseException("Supabase did not accept this URL and anon key (HTTP " + response.statusCode() + ")");
        }
    }

    /** @throws SupabaseException when the token cannot manage the project */
    public void verifyAccessToken(String projectRef, String accessToken) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(managementApi + "/v1/projects/" + projectRef))
                .header("Authorization", "Bearer " + accessToken).timeout(CHECK_TIMEOUT).GET().build());
        if (response.statusCode() != 200) {
            throw new SupabaseException("The access token cannot manage project " + projectRef + " (HTTP " + response.statusCode() + ")");
        }
    }

    /** Runs one migration's SQL. @throws SupabaseException with Supabase's message when it fails */
    public void runSql(String projectRef, String accessToken, String sql) {
        String body = objectMapper.writeValueAsString(Map.of("query", sql));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(managementApi + "/v1/projects/" + projectRef + "/database/query"))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .timeout(QUERY_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (response.statusCode() / 100 != 2) throw new SupabaseException(message(response));
    }

    private String message(HttpResponse<String> response) {
        String text = response.body() == null ? "" : response.body();
        try {
            JsonNode json = objectMapper.readTree(text);
            if (json.hasNonNull("message")) text = json.get("message").asText();
        } catch (RuntimeException notJson) {
            // keep the raw body
        }
        text = text.isBlank() ? "HTTP " + response.statusCode() : text;
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR) + "...";
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new SupabaseException("Supabase could not be reached: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SupabaseException("Interrupted while calling Supabase");
        }
    }
}
