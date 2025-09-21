package com.practice.client.tracking.infrastructure.db;

import java.time.Instant;

import com.practice.client.tracking.domain.dto.RecvOutputDTO;

public class StdOutWriter implements DatabaseWriter {
    @Override public void upsert(String icao24, long bucketStart, RecvOutputDTO dto) {
        System.out.println("UPSERT " + icao24 + " @ " + Instant.ofEpochMilli(bucketStart) + " | " + dto.getMessageType().orElse("?"));
    }
}
