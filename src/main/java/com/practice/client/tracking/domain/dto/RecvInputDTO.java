package com.practice.client.tracking.domain.dto;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RecvInputDTO {
    private final String streamUrl;                 // 例: https://api.example.com/v2/targets/stream
    private final String bearerToken;               // Authorization: Bearer ...
    private final String positionToken;             // 再接続で使う
    private final Map<String, String> extraParams;  // 任意の追加クエリ

    private RecvInputDTO(Builder b) {
        this.streamUrl = Objects.requireNonNull(b.streamUrl, "streamUrl");
        this.bearerToken = Objects.requireNonNull(b.bearerToken, "bearerToken");
        this.positionToken = b.positionToken;
        this.extraParams = b.extraParams == null ? Map.of() : Map.copyOf(b.extraParams);
    }
    public String getStreamUrl() { return streamUrl; }
    public String getBearerToken() { return bearerToken; }
    public Optional<String> getPositionToken() { return Optional.ofNullable(positionToken); }
    public Map<String, String> getExtraParams() { return extraParams; }
    public RecvInputDTO withPositionToken(String token) {
        return new Builder().streamUrl(streamUrl).bearerToken(bearerToken)
                .positionToken(token).extraParams(extraParams).build();
    }
    public static class Builder {
        private String streamUrl, bearerToken, positionToken;
        private Map<String, String> extraParams;
        public Builder streamUrl(String v) { this.streamUrl = v; return this; }
        public Builder bearerToken(String v) { this.bearerToken = v; return this; }
        public Builder positionToken(String v) { this.positionToken = v; return this; }
        public Builder extraParams(Map<String, String> v) { this.extraParams = v; return this; }
        public RecvInputDTO build() { return new RecvInputDTO(this); }
    }
}