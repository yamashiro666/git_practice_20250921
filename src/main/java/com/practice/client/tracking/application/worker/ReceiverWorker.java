package com.practice.client.tracking.application.worker;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import com.practice.client.tracking.domain.dto.RecvInputDTO;
import com.practice.client.tracking.domain.dto.RecvOutputDTO;
import com.practice.client.tracking.infrastructure.http.TrackingStreamClient;
import com.practice.client.tracking.infrastructure.persistence.token.TokenStore;

public class ReceiverWorker implements Runnable {
    private final TrackingStreamClient client;
    private final BlockingQueue<RecvOutputDTO> outQueue;
    private final AtomicBoolean stop;
    private RecvInputDTO ctx;
    private final TokenStore tokenStore;

    public ReceiverWorker(TrackingStreamClient client, RecvInputDTO initialCtx,
                   BlockingQueue<RecvOutputDTO> outQueue, AtomicBoolean stop, TokenStore store) {
        this.client = client; this.ctx = initialCtx; this.outQueue = outQueue; this.stop = stop; this.tokenStore = store;
        // 起動時に保存済みトークンがあれば“続きから”
        tokenStore.load().ifPresent(t -> this.ctx = this.ctx.withPositionToken(t));
    }

    @Override public void run() {
        try {
            client.authenticate(ctx);
            while (!stop.get()) {
                try {
                    RecvOutputDTO out = client.receive(ctx);
                    out.getPositionToken().ifPresent(t -> { tokenStore.save(t); ctx = ctx.withPositionToken(t); });

                    // 有界キュー。満杯なら最古を捨てて最新を優先（“最新だけ使う”要件に整合）
                    if (!outQueue.offer(out)) {
                        if (outQueue instanceof LinkedBlockingDeque<RecvOutputDTO> deq) {
                            deq.pollFirst();
                            deq.offerLast(out);
                        }
                    }
                } catch (IOException e) {
                    Thread.sleep(1000); // 軽いリトライ（本番は指数＋ジッタ推奨）
                }
            }
        } catch (Exception e) {
            System.err.println("[Receiver] fatal: " + e.getMessage());
        } finally { client.closeQuietly(); }
    }
}
