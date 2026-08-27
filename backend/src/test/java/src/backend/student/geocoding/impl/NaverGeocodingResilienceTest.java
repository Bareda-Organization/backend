package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;

/**
 * 지오코딩 어댑터의 보호 설정이 <b>실제로 걸리는지</b>를 도달한 호출 수로 고정한다(§7 규칙 11).
 *
 * <p>이 검사가 없으면 {@code max-attempts: 3} 을 적어 둔 채 실제로는 한 번만 부르는 상태와 구별할
 * 수단이 부재하다 — 실제로 그랬다. {@code @CircuitBreaker} 에 {@code fallbackMethod} 가 붙어
 * 있으면 안쪽 애스펙트가 첫 실패를 포트 예외로 삼켜 바깥 {@code @Retry} 에게는 성공으로 보이고,
 * 응답 코드는 {@code 503} 그대로라 어느 기능 테스트에도 드러나지 않는다. <b>애너테이션을 재배열하는
 * 변경이 이 클래스를 깨뜨려야 한다.</b>
 *
 * <p><b>두 방향을 함께 본다.</b> ①일시 실패는 세 번 부른다 ②서킷이 열린 동안은 재시도하지 않는다.
 * 앞만 보면 "무엇이든 세 번 재시도하는" 구현이 통과하고, 그때 서킷은 응답을 늦추기만 할 뿐 아무것도
 * 보호하지 않는다.
 *
 * <p>공급자 자리에 <b>로컬 HTTP 서버</b>를 세운다. 실 네이버를 부르면 판정이 네트워크·요금·NCP 계정
 * 상태에 매달려 그 결과가 코드에 대해 아무것도 말하지 않는다(Ruling 157). 호출 수를 세는 것이
 * 목적이라 응답 본문은 필요 없고, 500 을 돌려주는 것으로 족하다.
 */
@SpringBootTest(properties = {
        // 스텁은 재시도를 검사할 수 없다 — 이 검사의 대상이 어댑터의 애너테이션 자체다.
        // 테스트 전체 묶음은 build.gradle 이 stub 으로 고정하므로 여기서만 되돌린다.
        "geocoding.provider=naver",
        "geocoding.naver.key-id=test-key-id",
        "geocoding.naver.key=test-key",
        // 이 컨텍스트는 DB 를 쓰지 않는다. 컨텍스트마다 Hikari 가 기본 상한만큼 커넥션을 붙든 채
        // JVM 이 끝날 때까지 남아, 상한을 낮추지 않으면 이 클래스를 더한 것만으로 다른 컨텍스트가
        // "sorry, too many clients already" 로 못 뜬다(build.gradle 의 같은 설정과 같은 이유).
        // 1로는 못 뜬다 — 기동 중 Flyway 가 커넥션을 잡은 채 두 번째를 요구해 30초 뒤 시간 초과한다.
        "spring.datasource.hikari.maximum-pool-size=2"
})
class NaverGeocodingResilienceTest {

    /** 어떤 주소든 좌표를 못 얻는다 — 판정 대상은 결과가 아니라 <b>몇 번 불렀는가</b>다. */
    private static final String ANY_ADDRESS = "서울시 테스트로 100";

    /** 공급자에 도달한 요청 수 — 재시도가 걸렸는지를 이 값 하나로 가린다. */
    private static final AtomicInteger PROVIDER_HITS = new AtomicInteger();

    /**
     * 실패만 돌려주는 공급자 대역.
     *
     * <p>정적 초기화로 띄우는 것은 {@link DynamicPropertySource} 가 컨텍스트를 띄우기 <b>전에</b>
     * 포트를 알아야 하기 때문이다. 포트를 0으로 열어 OS 가 고르게 한다 — 고정 포트를 쓰면 같은
     * 저장소에서 에이전트가 둘 돌 때 한쪽이 점유해 다른 쪽이 기동 실패한다.
     */
    private static final HttpServer PROVIDER = startFailingProvider();

    @Autowired
    private GeocodingClient geocodingClient;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void 공급자_자리에_로컬_서버를_세운다(DynamicPropertyRegistry registry) {
        registry.add("geocoding.naver.base-url", () -> "http://localhost:" + PROVIDER.getAddress().getPort());
    }

    @AfterAll
    static void 공급자_대역을_내린다() {
        PROVIDER.stop(0);
    }

    /**
     * 서킷은 호출 이력을 누적하므로 테스트마다 되돌린다 — 되돌리지 않으면 앞 테스트가 남긴 실패로
     * 서킷이 열려 뒤 테스트의 판정이 <b>실행 순서에 따라</b> 갈린다.
     */
    @BeforeEach
    void 서킷과_계수기를_되돌린다() {
        circuitBreakerRegistry.circuitBreaker(NaverGeocodingClient.RESILIENCE_INSTANCE).reset();
        PROVIDER_HITS.set(0);
    }

    /**
     * 일시 실패는 {@code max-attempts} 만큼 다시 부른다 — 네트워크가 한 번 흔들렸다고 학부모에게
     * 곧바로 {@code 503} 이 나가지 않게 하는 것이 이 설정의 목적이다.
     *
     * <p>도달한 호출 수로 세는 이유는, 응답만 보면 재시도가 걸린 경우와 걸리지 않은 경우가 <b>똑같이
     * {@code GeocodingUnavailableException}</b> 이라 구별할 수단이 부재하기 때문이다.
     */
    @Test
    void 일시_실패는_설정한_횟수만큼_다시_부른다() {
        assertThat(maxAttempts())
                .as("max-attempts 가 1이면 아래 단언이 '재시도가 걸린 상태'와 '걸리지 않은 상태'를 구별하지 못한다")
                .isGreaterThan(1);

        assertThatThrownBy(() -> geocodingClient.geocode(ANY_ADDRESS))
                .as("공급자가 계속 실패하면 마지막에는 포트 예외로 끝난다")
                .isInstanceOf(GeocodingUnavailableException.class);

        assertThat(PROVIDER_HITS.get())
                .as("공급자에 도달한 호출 수 — 1이면 재시도가 걸리지 않은 것이다(fallbackMethod 가 @Retry 안쪽에 있는 형태)")
                .isEqualTo(maxAttempts());
    }

    /**
     * 서킷이 열린 동안은 <b>재시도하지 않는다</b> — 열린 서킷을 세 번 두드려 봐야 공급자에 닿지
     * 않고 {@code wait-duration} 만큼 응답만 늦어진다.
     *
     * <p>호출 수(0)만으로는 부족하다. 서킷이 열려 있으면 재시도를 하든 안 하든 공급자에는 닿지
     * 않아서다 — 그래서 재시도 지표를 함께 본다. {@code ignore-exceptions} 에서
     * {@code CallNotPermittedException} 이 빠지면 이 단언이 걸린다.
     */
    @Test
    void 서킷이_열려_있으면_재시도_없이_즉시_실패한다() {
        Retry retry = retryRegistry.retry(NaverGeocodingClient.RESILIENCE_INSTANCE);
        long 재시도_없이_실패한_호출 = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt();
        circuitBreakerRegistry.circuitBreaker(NaverGeocodingClient.RESILIENCE_INSTANCE).transitionToOpenState();

        assertThatThrownBy(() -> geocodingClient.geocode(ANY_ADDRESS))
                .as("서킷이 열려 있어도 호출부가 받는 것은 같은 포트 예외다 — 원인별 분기를 호출부에 만들지 않는다")
                .isInstanceOf(GeocodingUnavailableException.class);

        assertThat(PROVIDER_HITS.get())
                .as("서킷이 열렸는데 공급자에 요청이 나갔다 — 서킷이 아무것도 막지 않는 상태다")
                .isZero();
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt())
                .as("서킷 개방이 재시도 대상이 됐다 — ignore-exceptions 에 CallNotPermittedException 이 없다")
                .isEqualTo(재시도_없이_실패한_호출 + 1);
    }

    /** {@code application.yml} 의 값을 그대로 판정 기준으로 쓴다 — 테스트에 3을 옮겨 적으면 두 값이 갈린다. */
    private int maxAttempts() {
        return retryRegistry.retry(NaverGeocodingClient.RESILIENCE_INSTANCE).getRetryConfig().getMaxAttempts();
    }

    private static HttpServer startFailingProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                PROVIDER_HITS.incrementAndGet();
                byte[] body = "{\"error\":{\"errorCode\":\"500\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
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
