package com.practice.client.tracking.application.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import com.practice.client.tracking.domain.dto.RecvOutputDTO;
import com.practice.client.tracking.infrastructure.db.DatabaseWriter;

public class IntervalSampler implements Runnable {
    private final BlockingQueue<RecvOutputDTO> queue; // 有界推奨
    private final long windowMillis;
    private final DatabaseWriter dbWriter;

    public IntervalSampler(BlockingQueue<RecvOutputDTO> queue, long windowMillis, DatabaseWriter writer) {
        this.queue = queue; this.windowMillis = windowMillis; this.dbWriter = writer;
    }

    @Override public void run() {
        // 1) “今キューに溜まっている分だけ”を drain（投入中はロック待ち→次回に回る）
        var batch = new ArrayList<RecvOutputDTO>(Math.max(queue.size(), 16));
        queue.drainTo(batch);  // ← 要件「先頭1件ではなく、全件」を満たす

        if (batch.isEmpty()) return;

        // 2) ICAO24ごとに“最新上書き”で間引き
        Map<String, RecvOutputDTO> latestByIcao = new HashMap<>();
        for (var msg : batch) {
            if (!"target".equals(msg.getMessageType().orElse(""))) continue;  // 必要に応じて種別フィルタ
            msg.getIcao24().ifPresent(icao -> latestByIcao.put(icao, msg));
        }

        // 3) 現在の窓開始時刻をキー化（UTC壁時計ベース）
        long bucketStart = floorTo(windowMillis, System.currentTimeMillis());

        // 4) DBへUPSERT
        latestByIcao.forEach((icao, dto) -> dbWriter.upsert(icao, bucketStart, dto));
    }

    static long floorTo(long unitMillis, long epochMillis) {
        return (epochMillis / unitMillis) * unitMillis;
    }
}
