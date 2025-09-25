package test;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamWithRetry {

	private final HttpClient client;

	/** ベースURL */
	private final String baseUrl;
	/** 受信データ格納キュー */
	private LinkedBlockingQueue<OutputDto> que = new LinkedBlockingQueue<>();
	/** 認証トークン */
	private String authToken;
	/** 最大接続回数 */
	private int maxRetries;
	/** 接続回数 */
	private int attempt = 0;
	/** 接続時間 */
	private long timeOut;
	/** position_token保存ファイル */
	private String positionTokenFilePath;
	/** position_token */
	private volatile String positionToken;
	/** データ受信ストリーム処理停止フラグ */
	private volatile boolean streamStop = false;

	public StreamWithRetry() throws IOException {
        final String propsName = "stream.properties"; // src/main/resources 配下

        // プロパティをクラスパスから取得
        Properties p = new Properties();
        InputStream raw = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(propsName);
        if (raw == null) {
            throw new FileNotFoundException(
                "クラスパス上に " + propsName + " が見つかりません。src/main/resources に配置してください。");
        }
        try (Reader r = new InputStreamReader(raw, StandardCharsets.UTF_8)) {
            p.load(r); // key=value 形式を読み込み（UTF-8）
        }

        // 必須プロパティの取り出し
        baseUrl              = require(p, "stream.baseUrl");
        authToken            = require(p, "stream.authToken");
        maxRetries           = Integer.parseInt(require(p, "stream.retry.max-retries").trim());
        timeOut              = Long.parseLong(require(p, "stream.timeout").trim()); // ミリ秒
        positionTokenFilePath= require(p, "stream.positionTokenFile");

        // クライアント作成
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeOut))
                .build();
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("必須プロパティが未設定: " + key);
        }
        return v;
    }

	public OutputDto receive() throws InterruptedException {
		return que.poll();
	}

	/** 受信ループ：例外時に catch の中で N 回まで再接続 */
	public void runStream(InputDto dto) throws IOException, URISyntaxException, InterruptedException {
		String positionToken = null; // サーバが返すトークンで継続（任意）
		
		Pattern p = Pattern.compile("\"(position_token)\"\\s*:\\s*\"([^\"]+)\"");
		
		// クエリプションの文字列を作成
		String queryStr = "";
		List<Map<String, String>> opts = dto.getOpt();
		if (opts != null && !opts.isEmpty()) {
		    StringBuilder sb = new StringBuilder();
		    for (Map<String, String> optMap : opts) {
		        if (optMap == null || optMap.isEmpty()) continue;
		        for (Map.Entry<String, String> e : optMap.entrySet()) {
		            String k = e.getKey();
		            if (k == null) continue; // キーは必須
		            String v = e.getValue() == null ? "" : e.getValue();

		            if (sb.length() > 0) sb.append('&');
		            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8));
		            sb.append('=');
		            sb.append(URLEncoder.encode(v, StandardCharsets.UTF_8));
		        }
		    }
		    queryStr = sb.toString();
		}

		while (!streamStop) {

			// URL組み立て
			String baseWithQuery = (queryStr == null || queryStr.isEmpty())
					? baseUrl
					: (baseUrl.contains("?") ? baseUrl + "&" + queryStr : baseUrl + "?" + queryStr);
			String uri = (positionToken == null)
					? baseWithQuery
					: baseWithQuery + "&position_token=" + URLEncoder.encode(positionToken, StandardCharsets.UTF_8);
			
			HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
					.header("Authorization", "Bearer " + authToken)
					.GET()
					.build();

			// データストリーム受信
			try {
				HttpResponse<InputStream> res =
						client.send(req, HttpResponse.BodyHandlers.ofInputStream());

				int sc = res.statusCode();
				if (sc == 200) {
					// ストリーム本体を逐次読み取り
					try (BufferedReader br = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
						String line;
						while (!streamStop && (line = br.readLine()) != null) {

							// position_tokenの取得
							Matcher m = p.matcher(line);
							if (m.find()) {
								// m.group(1) がプロパティ名 "position_token"
								String tokenVal = m.group(2); // 値
								positionToken = tokenVal;      // ローカル継続用
								this.positionToken = tokenVal; // フィールドにも反映（retryLoopでファイルへ書く）
							}

							// Dtoに受信データをセット
							OutputDto outDto = new OutputDto();
							outDto.setResponseData(line);

							// キューにDtoをセット
							if (!que.offer(outDto)) {
								// 満杯の場合は最初の要素を削除する
								que.poll();
								que.offer(outDto);
							}

						}
						// リトライ処理: 例外なくストリームが閉じる
						retryLoop();
						continue;
					}
				} else if (sc == 429 || (sc >= 500 && sc <= 599)) {
					// リトライ処理: サーバ系エラー
					retryLoop();
					continue;
				} else {
					throw new IOException("Non-retryable HTTP status: " + sc);
				}

			// データストリーム受信処理失敗
			} catch (IOException e) {
				// Interrupted 系は優先して拾い処理を中断する
				if (e instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("interrupted during IO/retry");
				}
				// リトライ処理: データストリーム受信処理失敗
				retryLoop();
			}
		}
				
		// リトライ回数上限に達したら例外をスロー
		RuntimeException re = new RuntimeException("reconnect retries exhausted");
		throw re;
	}

	/** 実際の「catchの中で再接続」パターン（IOException 用） */
	private void retryLoop() throws IOException, URISyntaxException, InterruptedException {
		// リトライ回数判定
		if (attempt < maxRetries) {
			attempt++;
		} else {
			streamStop = true;
		}
		
		try {
			URL u = StreamWithRetry.class.getResource(positionTokenFilePath);
			String toWrite = (this.positionToken == null) ? "" : this.positionToken;
			Files.writeString(Path.of(u.toURI()), toWrite, StandardCharsets.UTF_8);
		} catch (IOException | URISyntaxException e) {
			System.err.println("[retryLoop] failed to write positionToken to file: " + e.getMessage());
			throw new IOException("リトライ処理:position_tokenファイルの書き込み処理に失敗しました。", e);
		}
		
	}
}
