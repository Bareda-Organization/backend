package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;

/**
 * DA-9(Ruling 899) — 지오코딩 · 장소 검색은 <b>동시 호출 8개</b>까지만 공급자에 보내고, 9번째는 기다리지 않고 곧바로 포트 예외(대체 응답)를
 * 받는다. 공급자가 느려질 때 요청 스레드가 줄줄이 붙들리는 것을 막는다. 자동완성(후보 조회)은 재시도하지 않는다 — 입력할 때마다 다시
 * 부르는 화면이라 재시도가 느린 공급자 앞에서 호출만 3배로 늘린다.
 *
 * <p>공급자 자리에는 <b>느린 로컬 서버</b>를 세워(요청마다 {@value #SLOW_MILLIS}ms 뒤 응답) 동시 호출이 실제로 겹치게 한다.
 */
@SpringBootTest(properties = {
        "geocoding.provider=naver",
        "app.place-search.provider=naver",
        "geocoding.naver.key-id=test-key-id",
        "geocoding.naver.key=test-key",
        "spring.datasource.hikari.maximum-pool-size=2"
})
class NaverGeocodingBulkheadTest {

    private static final int SLOW_MILLIS = 2000;

    private static final int LIMIT = 8;

    /** 공급자에 도달한 요청 수와 같은 순간에 처리 중이던 요청 수의 최댓값. */
    private static final AtomicInteger HITS = new AtomicInteger();

    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private static final AtomicInteger PEAK = new AtomicInteger();

    private static final HttpServer PROVIDER = startSlowProvider();

    @Autowired private GeocodingClient geocodingClient;
    @Autowired private PlaceSearchClient placeSearchClient;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void 공급자_자리에_느린_서버를_세운다(DynamicPropertyRegistry registry) {
        registry.add("geocoding.naver.base-url", () -> "http://localhost:" + PROVIDER.getAddress().getPort());
        registry.add("app.place-search.naver.base-url", () -> "http://localhost:" + PROVIDER.getAddress().getPort());
    }

    @AfterAll
    static void 공급자_대역을_내린다() {
        PROVIDER.stop(0);
    }

    @BeforeEach
    void 서킷과_계수기를_되돌린다() {
        circuitBreakerRegistry.circuitBreaker(NaverGeocodingClient.RESILIENCE_INSTANCE).reset();
        circuitBreakerRegistry.circuitBreaker(NaverPlaceSearchClient.RESILIENCE_INSTANCE).reset();
        HITS.set(0);
        ACTIVE.set(0);
        PEAK.set(0);
    }

    @Test
    @DisplayName("DA-9 — 지오코딩 주소 변환은 동시 8개까지, 9번째는 기다리지 않고 포트 예외다")
    void 지오코딩_주소_변환_9번째_동시_호출은_즉시_거부된다() throws Exception {
        assertNinthRejectedImmediately(() -> geocodingClient.geocode("서울시 테스트로 100"));
    }

    @Test
    @DisplayName("DA-9 — 지오코딩 자동완성은 동시 8개까지, 9번째는 기다리지 않고 포트 예외다")
    void 지오코딩_자동완성_9번째_동시_호출은_즉시_거부된다() throws Exception {
        assertNinthRejectedImmediately(() -> geocodingClient.candidates("서울시 테스트로"));
    }

    @Test
    @DisplayName("DA-9 — 장소 검색은 동시 8개까지, 9번째는 기다리지 않고 포트 예외다")
    void 장소_검색_9번째_동시_호출은_즉시_거부된다() throws Exception {
        assertNinthRejectedImmediately(() -> placeSearchClient.search("신정역"));
    }

    @Test
    @DisplayName("DA-9 — 자동완성 후보 조회는 공급자가 실패해도 다시 부르지 않는다")
    void 자동완성은_재시도하지_않는다() {
        RESPONSE_STATUS.set(500);
        try {
            assertThatThrownBy(() -> geocodingClient.candidates("서울시 테스트로"))
                    .isInstanceOf(GeocodingUnavailableException.class);
        } finally {
            RESPONSE_STATUS.set(200);
        }

        assertThat(HITS.get()).as("공급자에 도달한 호출 수").isEqualTo(1);
    }

    private void assertNinthRejectedImmediately(Callable<?> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(LIMIT);
        try {
            List<Future<?>> inFlight = new ArrayList<>();
            for (int i = 0; i < LIMIT; i++) {
                inFlight.add(pool.submit(call));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ACTIVE.get() < LIMIT && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ACTIVE.get()).as("느린 공급자에서 동시에 처리 중인 요청").isEqualTo(LIMIT);

            long started = System.nanoTime();
            assertThatThrownBy(call::call).as("9번째 동시 호출").isInstanceOf(GeocodingUnavailableException.class);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(elapsedMillis).as("9번째 호출이 대체 응답을 받기까지(ms)").isLessThan(SLOW_MILLIS / 3);
            assertThat(PEAK.get()).as("공급자에 동시에 닿은 최대 요청 수").isEqualTo(LIMIT);
            for (Future<?> future : inFlight) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);

    private static HttpServer startSlowProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(24));
            server.createContext("/", exchange -> {
                HITS.incrementAndGet();
                PEAK.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
                try {
                    if (RESPONSE_STATUS.get() == 200) {
                        Thread.sleep(SLOW_MILLIS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    ACTIVE.decrementAndGet();
                }
                String json = RESPONSE_STATUS.get() == 200 ? "{\"addresses\":[],\"items\":[]}" : "{\"error\":{}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
