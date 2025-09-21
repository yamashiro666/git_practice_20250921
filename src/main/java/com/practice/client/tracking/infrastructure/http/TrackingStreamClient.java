package com.practice.client.tracking.infrastructure.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.client.tracking.domain.dto.RecvInputDTO;
import com.practice.client.tracking.domain.dto.RecvOutputDTO;

public class TrackingStreamClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private BufferedReader currentReader;
    private RecvInputDTO currentCtx;
    private static final ObjectMapper M = new ObjectMapper();

    /** 認証：変更なし（トークン存在チェック等のフック） */
    public void authenticate(RecvInputDTO dto) {
        if (dto.getBearerToken() == null || dto.getBearerToken().isBlank()) {
            throw new IllegalArgumentException("Bearer token is required");
        }
    }

    /** ご指定のシグネチャ：1行読み→RecvOutputDTO */
    public RecvOutputDTO receive(RecvInputDTO dto) throws IOException, InterruptedException {
        ensureOpen(dto);
        String line = currentReader.readLine();
        if (line == null) throw new IOException("Stream closed by server");

        var b = new RecvOutputDTO.Builder().rawJson(line).receivedAt(Instant.now());
        try {
            JsonNode n = M.readTree(line);
            if (n.has("position_token")) {
                b.messageType("position_token").positionToken(n.get("position_token").asText());
            } else if (n.has("target")) {
                b.messageType("target");
                JsonNode t = n.get("target");
                String icao = firstNonBlank(
                        textOrNull(t, "icao_address"),
                        textOrNull(t, "hex"),
                        textOrNull(t, "icao24"));
                if (icao != null) b.icao24(icao);
            } else if (n.has("status")) {
                b.messageType("status");
            }
        } catch (Exception ignore) {}
        return b.build();
    }

    private void ensureOpen(RecvInputDTO dto) throws IOException, InterruptedException {
        if (currentReader != null && dto.equals(currentCtx)) return;
        closeQuietly();

        String url = buildUrl(dto);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + dto.getBearerToken())
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Non-OK status: " + resp.statusCode());
        }
        currentReader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        currentCtx = dto;
    }

    private static String buildUrl(RecvInputDTO dto) {
        StringBuilder sb = new StringBuilder(dto.getStreamUrl());
        char sep = dto.getStreamUrl().contains("?") ? '&' : '?';
        for (var e : dto.getExtraParams().entrySet()) {
            sb.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue())); sep = '&';
        }
        if (dto.getPositionToken().isPresent()) {
            sb.append(sep).append("position_token=").append(enc(dto.getPositionToken().get()));
        }
        return sb.toString();
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String textOrNull(JsonNode n, String k) { return n != null && n.has(k) ? n.get(k).asText() : null; }
    private static String firstNonBlank(String... xs) { for (var x : xs) if (x != null && !x.isBlank()) return x; return null; }

    public void closeQuietly() {
        try { if (currentReader != null) currentReader.close(); } catch (Exception ignore) {}
        currentReader = null; currentCtx = null;
    }
}