package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.websocket.WebSocketBroadcastGateway;

/**
 * 유령 세션 방지 ② — 하트비트를 끈({@code heart-beat:0,0}) 클라이언트가 조용히 사라져도 유휴 제한 안에 서버가 세션을 치운다
 * (R46-CONNCAP, Ruling 693).
 *
 * <p>하트비트를 협상하지 않으면 Spring 의 읽기 감시가 꺼져 서버는 이 세션을 영원히 살아 있다고 본다 — 다음 방송 쓰기가 실패할
 * 때까지 메모리에 남는다. 그 구멍을 {@code app.ws.idle-timeout-ms}(운영 60초)가 막고, 시험은 3초로 줄여 넣는다. Tomcat 이 유휴를
 * 점검하는 주기가 10초라 실제 정리는 3~13초 사이다.
 *
 * <p>두 세션 모두 방송 채널을 구독하고 시험이 방송을 계속 쏜다 — 서버가 쓰기를 계속해도 읽기가 없으면 닫혀야 한다(표준
 * {@code maxSessionIdleTimeout} 은 읽기·쓰기가 둘 다 유휴일 때만 닫아 이 경우를 놓친다). 같은 시간 동안 줄바꿈 하트비트를 1초마다
 * 보낸 세션은 남아야 한다 — 유휴 제한이 살아 있는 클라이언트를 잘못 자르지 않는다는 대조군이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.ws.idle-timeout-ms=3000")
class StompIdleTimeoutReapTest {

    private static final String DESTINATION = "/topic/academy/1/live";

    @LocalServerPort
    private int port;

    @Autowired
    private WebSocketBroadcastGateway gateway;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private StompSessionExpiry sessionExpiry;

    @Test
    @DisplayName("heart-beat:0,0 으로 연결해 조용한 클라이언트는 서버가 방송을 계속 써도 유휴 제한 안에 정리되고 줄을 보낸 클라이언트는 남는다")
    void 하트비트를_끈_조용한_세션은_유휴_제한_안에_정리된다() throws Exception {
        double baseSessions = activeSessions();
        int baseExpiry = sessionExpiry.trackedSessionCount();
        String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        try (RawStompClient silent = RawStompClient.connect(port, token, "0,0");
                RawStompClient alive = RawStompClient.connect(port, token, "0,0")) {
            silent.subscribe(DESTINATION);
            alive.subscribe(DESTINATION);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(activeSessions()).as("연결 직후 활성 세션 집합").isEqualTo(baseSessions + 2));

            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (!silent.awaitClosed(Duration.ofSeconds(1))) {
                assertThat(System.nanoTime()).as("조용한 세션이 유휴 제한(3초 + 점검 주기 10초) 안에 닫혀야 한다").isLessThan(deadline);
                gateway.send(DESTINATION, "idle_probe", 7L, OffsetDateTime.now(), Map.of("tick", 1));
                alive.sendHeartbeat();
            }

            assertThat(alive.isOpen()).as("줄을 계속 보낸 세션은 닫히지 않는다").isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(activeSessions()).as("조용한 세션만 활성 세션 집합에서 빠진다").isEqualTo(baseSessions + 1);
                assertThat(sessionExpiry.trackedSessionCount()).as("조용한 세션만 만료 시각 맵에서 빠진다").isEqualTo(baseExpiry + 1);
            });
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(activeSessions()).as("전부 닫은 뒤 활성 세션 집합").isEqualTo(baseSessions);
            assertThat(sessionExpiry.trackedSessionCount()).as("전부 닫은 뒤 만료 시각 맵").isEqualTo(baseExpiry);
        });
    }

    private double activeSessions() {
        return registry.get("schoolbus.stomp.sessions").gauge().value();
    }
}
