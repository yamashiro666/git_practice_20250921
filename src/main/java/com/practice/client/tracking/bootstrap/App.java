package com.practice.client.tracking.bootstrap;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.practice.client.tracking.application.worker.IntervalSampler;
import com.practice.client.tracking.application.worker.ReceiverWorker;
import com.practice.client.tracking.config.AppConfig;
import com.practice.client.tracking.domain.dto.RecvInputDTO;
import com.practice.client.tracking.domain.dto.RecvOutputDTO;
import com.practice.client.tracking.infrastructure.db.StdOutWriter;
import com.practice.client.tracking.infrastructure.http.TrackingStreamClient;
import com.practice.client.tracking.infrastructure.persistence.token.FileTokenStore;

public class App {
    public static void main(String[] args) {
        // プロパティ：sampling.window.millis=30000 を想定
        AppConfig conf = AppConfig.load(Path.of("app.properties"));

        String url = "https://api.example.com/v2/targets/stream"; // 実URLへ
        String token = System.getenv("API_TOKEN");

        RecvInputDTO input = new RecvInputDTO.Builder()
                .streamUrl(url)
                .bearerToken(token)
                // .extraParams(Map.of("country", "JP"))
                .build();

        var client = new TrackingStreamClient();
        // 有界キュー。LinkedBlockingDeque で“最古ドロップ”が書きやすい
        var queue = new LinkedBlockingDeque<RecvOutputDTO>(10_000);
        var stop  = new AtomicBoolean(false);
        var tokenStore = new FileTokenStore(Path.of("position.token"));

        // スレッド1：受信
        var t1 = new Thread(new ReceiverWorker(client, input, queue, stop, tokenStore), "stream-receiver");
        t1.start();

        // スレッド2：間引き（プロパティの間隔で実行）
        var sampler = new IntervalSampler(queue, conf.samplingWindowMillis, new StdOutWriter());
        var sch = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "sampler");
            th.setDaemon(true); return th;
        });
        // flushが長引いた場合の重複実行を避けるため“FixedDelay”推奨
        sch.scheduleWithFixedDelay(sampler, conf.samplingWindowMillis, conf.samplingWindowMillis, TimeUnit.MILLISECONDS);

        // 終了フック
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.set(true);
            sch.shutdownNow();
            client.closeQuietly();
        }));
    }
}
