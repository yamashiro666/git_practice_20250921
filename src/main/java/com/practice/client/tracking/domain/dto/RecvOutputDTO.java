package com.practice.client.tracking.domain.dto;

import java.time.Instant;
import java.util.Optional;

public class RecvOutputDTO {
    private final String rawJson;
    private final Instant receivedAt;
    private final String messageType;       // "target" / "status" / "position_token" 等（推定）
    private final String positionToken;     // 行から抽出できた場合
    private final String icao24;            // Mode S 24bitアドレス（抽出できた場合）

    private RecvOutputDTO(Builder b) {
        this.rawJson = b.rawJson;
        this.receivedAt = b.receivedAt == null ? Instant.now() : b.receivedAt;
        this.messageType = b.messageType;
        this.positionToken = b.positionToken;
        this.icao24 = b.icao24;
    }
    public String getRawJson() { return rawJson; }
    public Instant getReceivedAt() { return receivedAt; }
    public Optional<String> getMessageType() { return Optional.ofNullable(messageType); }
    public Optional<String> getPositionToken() { return Optional.ofNullable(positionToken); }
    public Optional<String> getIcao24() { return Optional.ofNullable(icao24); }

    public static class Builder {
        private String rawJson, messageType, positionToken, icao24;
        private Instant receivedAt;
        public Builder rawJson(String v) { this.rawJson = v; return this; }
        public Builder receivedAt(Instant v) { this.receivedAt = v; return this; }
        public Builder messageType(String v) { this.messageType = v; return this; }
        public Builder positionToken(String v) { this.positionToken = v; return this; }
        public Builder icao24(String v) { this.icao24 = v; return this; }
        public RecvOutputDTO build() { return new RecvOutputDTO(this); }
    }
}