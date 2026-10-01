package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 유령 세션 방지 ① — 하트비트를 협상한 클라이언트가 소리 없이 사라지면 서버가 30초대에 세션을 치운다(R46-CONNCAP, Ruling 694).
 *
 * <p>Spring 단순 브로커는 협상한 하트비트가 10초일 때 무수신 {@code max(10, 10) × 3 = 30초} 가 지나면 세션·구독을 지우고, 점검
 * 주기(10초)라 실제로는 30~40초에 소켓을 닫는다 — 그 뒤 연결 해제 이벤트가 우리 쪽 맵 둘({@code StompSessionMetrics} 의 활성 집합 ·
 * {@code StompSessionExpiry} 의 만료 시각 맵)에서도 세션을 뺀다. 소스 읽기가 아니라 실제 서버·원시 클라이언트로 확인한다.
 *
 * <p>컨텍스트를 일부러 따로 쓴다({@code app.ws.idle-timeout-ms} 를 기본값 그대로 명시) — 다른 시험의 세션이 개수에 섞이지 않게.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.ws.idle-timeout-ms=60000")
class StompHeartbeatReapTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private StompSessionExpiry sessionExpiry;

    @Test
    @DisplayName("하트비트 10초로 연결한 뒤 송신을 멈춘 클라이언트는 30~40초 안에 서버에서 정리된다 — 두 맵에서도 빠진다")
    void 하트비트로_연결한_뒤_조용해진_세션은_30초대에_정리된다() throws Exception {
        double baseSessions = activeSessions();
        int baseExpiry = sessionExpiry.trackedSessionCount();
        String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        try (RawStompClient silent = RawStompClient.connect(port, token, "10000,10000")) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(activeSessions()).as("연결 직후 활성 세션 집합").isEqualTo(baseSessions + 1);
                assertThat(sessionExpiry.trackedSessionCount()).as("연결 직후 만료 시각 맵").isEqualTo(baseExpiry + 1);
            });

            long start = System.nanoTime();
            assertThat(silent.awaitClosed(Duration.ofSeconds(50))).as("송신을 멈춘 지 50초 안에 서버가 소켓을 닫는다").isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("하트비트 10초 × 3 = 30초 전에는 닫지 않는다")
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(25));
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(activeSessions()).as("정리 뒤 활성 세션 집합").isEqualTo(baseSessions);
            assertThat(sessionExpiry.trackedSessionCount()).as("정리 뒤 만료 시각 맵").isEqualTo(baseExpiry);
        });
    }

    private double activeSessions() {
        return registry.get("schoolbus.stomp.sessions").gauge().value();
    }
}
