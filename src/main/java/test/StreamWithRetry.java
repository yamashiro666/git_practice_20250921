package test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URI;
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
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
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
	private int attempt;
	private long timeOut;
	private String positionTokenFilePath;
	private volatile String positionToken;
	private volatile boolean streamStop = false;
	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

	public StreamWithRetry() throws IOException {

		Properties p = new Properties();
		InputStream in = Files.newInputStream(Path.of(null));
		p.load(in);

		baseUrl = p.getProperty("stream.baseUrl");
		authToken = p.getProperty("stream.authToken");
		maxRetries = Integer.parseInt(p.getProperty("stream.retry.max-retries"));
		timeOut = Long.parseLong(p.getProperty("stream.timeout"));
		positionTokenFilePath = p.getProperty("stream.positionTokenFile");
		
		// クライアント作成
		client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(timeOut))
				.build();

	}

	public OutputDto receive() throws InterruptedException {
		return que.poll();
	}

	/** 受信ループ：例外時に catch の中で N 回まで再接続 */
	public void runStream(InputDto dto) throws InterruptedException {
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

			// 指示: URIの末尾にはqueryStrをつけてください。
			String uri = (positionToken == null) ? baseUrl : baseUrl + "&position_token=" + URLEncoder.encode(positionToken, StandardCharsets.UTF_8);
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
	private void retryLoop() throws InterruptedException {
		// リトライ処理
		attempt = 0;
		if (attempt < maxRetries) {
			attempt++;
		} else {
			streamStop = true;
		}
		
		// 指示: this.positionTokenを、this.positionTokenFilePathに書き込みます。
		// 書き込みの際は、上書きで書き込んでください。
		try {
			String toWrite = (this.positionToken == null) ? "" : this.positionToken;
			Files.writeString(Path.of(this.positionTokenFilePath), toWrite, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// ログのみ（再スローしない）：書き込み失敗してもストリーム再接続自体は継続したい
			System.err.println("[retryLoop] failed to write positionToken to file: " + e.getMessage());
		}
		
	}
}
