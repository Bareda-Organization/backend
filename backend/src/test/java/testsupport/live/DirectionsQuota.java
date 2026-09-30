package testsupport.live;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.springframework.core.env.Environment;

/**
 * 실 Directions 시험({@code @Tag("live")})을 <b>일일 한도가 바닥났을 때만</b> 건너뛴다(Ruling 361 — Directions 15
 * 는 {@code HTTP 400 "사용량이 한도를 초과했습니다"}).
 *
 * <p>어댑터({@code NaverDirectionsClient})는 공급자 오류를 직선거리 근사로 삼켜 시험에는 {@code fallbackUsed=true}
 * 만 보이고 원인은 안 보인다. 그래서 시험 시작 전에 같은 자격증명·경로로 한 번 직접 불러 응답 본문을 본다.
 * <b>한도 초과 문구가 있는 응답만</b> 건너뛴다 — 같은 400 이라도 다른 사유(출발지·도착지 동일 등)는 어댑터
 * 결함의 신호라 그대로 시험이 돌아 실패해야 한다.
 */
public final class DirectionsQuota {

    private static final String QUOTA_MESSAGE = "사용량이 한도를 초과";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /** 서울시청 → 강남역(경도,위도) — {@code NaverDirectionsClientLiveTest} 가 쓰는 두 지점이다. */
    private static final String PROBE_QUERY = "?start=126.978000,37.566500&goal=127.027600,37.497900";

    /** 한 JVM 에서 한 번만 부른다 — 시험 클래스마다 부르면 그만큼 한도를 쓴다. */
    private static final AtomicReference<Boolean> EXHAUSTED = new AtomicReference<>();

    private DirectionsQuota() {
    }

    /** 어댑터가 읽는 것과 같은 설정({@code app.routing.map.naver.*})으로 한 번 확인하고, 초과면 건너뛴다. */
    public static void assumeQuotaLeft(Environment env) {
        boolean exhausted = EXHAUSTED.updateAndGet(cached -> cached != null ? cached : probe(
                env.getRequiredProperty("app.routing.map.naver.base-url"),
                env.getRequiredProperty("app.routing.map.naver.driving-path"),
                env.getProperty("app.routing.map.naver.key-id", ""),
                env.getProperty("app.routing.map.naver.key", "")));
        Assumptions.assumeFalse(exhausted, "Directions 일일 한도 초과 — 실 API 시험을 건너뛴다");
    }

    /** 주소를 인자로 받는 본체 — 로컬 가짜 서버로 판정을 시험하려고 갈랐다. */
    static void assumeQuotaLeft(String baseUrl, String path, String keyId, String key) {
        Assumptions.assumeFalse(probe(baseUrl, path, keyId, key), "Directions 일일 한도 초과 — 실 API 시험을 건너뛴다");
    }

    /** 한도 초과 응답이면 {@code true}. 연결 실패·타임아웃은 한도 문제가 아니므로 {@code false} 다. */
    static boolean probe(String baseUrl, String path, String keyId, String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path + PROBE_QUERY))
                .header("x-ncp-apigw-api-key-id", keyId)
                .header("x-ncp-apigw-api-key", key)
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            return isQuotaExceeded(response.statusCode(), response.body());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 400 이면서 본문에 한도 초과 문구가 있을 때만 참 — 다른 400 은 어댑터 결함일 수 있어 건너뛰지 않는다. */
    static boolean isQuotaExceeded(int status, String body) {
        return status == 400 && body != null && body.contains(QUOTA_MESSAGE);
    }
}
