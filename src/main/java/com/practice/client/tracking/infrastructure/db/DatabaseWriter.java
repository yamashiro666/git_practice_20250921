package com.practice.client.tracking.infrastructure.db;

import com.practice.client.tracking.domain.dto.RecvOutputDTO;

public interface DatabaseWriter {
    void upsert(String icao24, long bucketStartMillis, RecvOutputDTO dto);
}