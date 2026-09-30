package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.zaxxer.hikari.HikariDataSource;

import io.lettuce.core.ClientOptions;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.location.proximity.ProximityNotificationService;
import testsupport.clock.SeedDateClockConfig;
import testsupport.redis.RedisFreeze;

/**
 * Redis 가 응답하지 않을 때(W12-01 · BR-166) — 명령이 Lettuce 기본값(60초)이 아니라 설정한 상한 안에 실패하고,
 * 근접 판정은 Redis 를 기다리는 동안 DB 커넥션을 쥐지 않는다.
 *
 * <p>응답 없음은 이 시험 JVM 의 전용 Redis 컨테이너를 얼려 만든다({@link RedisFreeze}). {@code @Transactional} 을
 * 쓰지 않는다 — 시험 트랜잭션이 커넥션을 쥐면 서비스가 쥔 커넥션을 셀 수 없다. 시드 회차 R3(학원 1, moving)과
 * 그 학생 2·보호자 5·기사(계정 14)를 쓴다 — 위치 수신 시험이 적재한 행만 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeedDateClockConfig.class)
class RedisUnresponsiveTest {

    private static final String POSITION = "/api/v1/students/2/bus-position";

    private static final long ACADEMY_A = 1L;

    private static final long RUN_MOVING_ID = 3L;

    private static final long GUARDIAN_ACCOUNT = 5L;

    /** R3 에 배치된 기사(manager 2) 의 계정. */
    private static final long DRIVER_ACCOUNT = 14L;

    /** 위치 수신 시험이 심는 좌표 — 이 값으로 적재된 행을 찾고 지운다. */
    private static final String MARKER_LAT = "37.444441";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProximityNotificationService proximityNotificationService;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    /**
     * 학부모 버스 위치 조회가 얼린 Redis 앞에서 1초 안에 끝난다 — 응답 코드는 보지 않는다(대체 조회는 BR-167 의
     * 관심사). 끝난 뒤 커넥션이 풀로 돌아왔는지도 본다.
     */
    @Test
    @DisplayName("BR-166 — 응답 없는 Redis 앞에서 위치 조회가 1초 안에 끝난다")
    void 응답_없는_Redis_앞에서_위치_조회가_1초_안에_끝난다() throws Exception {
        String token = 보호자_토큰();
        mockMvc.perform(get(POSITION).header("Authorization", token)); // 첫 요청의 초기화 비용을 재기 전에 치른다

        long elapsedNanos;
        try (RedisFreeze ignored = RedisFreeze.start()) {
            long start = System.nanoTime();
            mockMvc.perform(get(POSITION).header("Authorization", token));
            elapsedNanos = System.nanoTime() - start;
        }

        assertThat(Duration.ofNanos(elapsedNanos)).as("Redis 명령 시간 상한").isLessThan(Duration.ofSeconds(1));
        assertThat(hikari().getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    /**
     * 근접 판정이 Redis 를 기다리는 동안 DB 커넥션을 쥐지 않는다 — 읽기가 트랜잭션 안에 있으면 한 회차가 Redis 를
     * 기다리는 동안 커넥션 1개가 묶이고, 운행 중 회차 수만큼 곱해져 풀이 마른다.
     */
    @Test
    @DisplayName("BR-166 — 근접 판정은 Redis 를 기다리는 동안 DB 커넥션을 쥐지 않는다")
    void 근접_판정은_Redis_를_기다리는_동안_DB_커넥션을_쥐지_않는다() throws Exception {
        int maxActiveWhileWaiting = 0;
        int samplesWhileWaiting = 0;
        CompletableFuture<Void> judging;
        try (RedisFreeze ignored = RedisFreeze.start()) {
            judging = CompletableFuture.runAsync(() -> proximityNotificationService.judgeOne(RUN_MOVING_ID, ACADEMY_A));
            // 판정이 아직 Redis 를 기다리는 동안(끝나지 않은 동안)에만 표본을 센다 — 명령 상한(1초) 안에서 여러 번 잰다.
            for (int i = 0; i < 5; i++) {
                Thread.sleep(100);
                if (judging.isDone()) {
                    break;
                }
                samplesWhileWaiting++;
                maxActiveWhileWaiting = Math.max(maxActiveWhileWaiting,
                        hikari().getHikariPoolMXBean().getActiveConnections());
            }
        }
        judging.handle((ok, e) -> null).get(10, TimeUnit.SECONDS);

        assertThat(samplesWhileWaiting)
                .as("판정이 Redis 대기에 들어가지 못했다 — 0 이 나와도 커넥션을 안 쥔다는 증거가 못 된다(공허 통과)")
                .isGreaterThanOrEqualTo(3);
        assertThat(maxActiveWhileWaiting).as("Redis 대기 중 쥔 DB 커넥션 수").isZero();
    }

    /**
     * 연결이 끊긴 동안 들어온 명령을 쌓아 두지 않고 즉시 거절한다 — Lettuce 기본값은 재연결까지 받아 두었다가
     * 시간 상한까지 기다린다. 끊김은 공유 컨테이너를 멈춰야 재현돼(포트가 바뀔 수 있다) 설정값으로 고정한다.
     */
    @Test
    @DisplayName("BR-166 — 연결이 끊긴 동안의 명령은 즉시 거절한다")
    void 연결이_끊긴_동안의_명령은_즉시_거절한다() {
        ClientOptions options = ((LettuceConnectionFactory) redisConnectionFactory).getClientConfiguration()
                .getClientOptions().orElseThrow();

        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    /**
     * 위치 수신은 Redis 가 답하지 않아도 성공한다 — 이력({@code run_position})은 커밋된 뒤라 되돌릴 수 없고, Redis 최신
     * 좌표 갱신 실패는 삼켜 다음 송신에 맡긴다(계획서 §3.1 "확인할 것"). 요청이 끝나면 커넥션도 풀로 돌아온다.
     */
    @Test
    @DisplayName("BR-166 — 응답 없는 Redis 앞에서도 위치 수신은 이력을 남기고 204")
    void 응답_없는_Redis_앞에서도_위치_수신은_이력을_남기고_204() throws Exception {
        String body = """
                {"lat": %s, "lng": 127.444441, "recorded_at": "%s"}
                """.formatted(MARKER_LAT, OffsetDateTime.now(clock));
        try {
            try (RedisFreeze ignored = RedisFreeze.start()) {
                mockMvc.perform(post("/api/v1/runs/%d/position".formatted(RUN_MOVING_ID))
                                .header("Authorization", "Bearer " + tokenProvider.createAccessToken(DRIVER_ACCOUNT,
                                        ACADEMY_A, Role.DRIVER, AccountStatus.ACTIVE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNoContent());
            }

            Integer saved = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM run_position WHERE run_id = ? AND lat = ?::numeric", Integer.class,
                    RUN_MOVING_ID, MARKER_LAT);
            assertThat(saved).as("커밋된 위치 이력").isEqualTo(1);
            assertThat(hikari().getHikariPoolMXBean().getActiveConnections()).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM run_position WHERE run_id = ? AND lat = ?::numeric", RUN_MOVING_ID,
                    MARKER_LAT);
        }
    }

    private HikariDataSource hikari() throws Exception {
        return dataSource.unwrap(HikariDataSource.class);
    }

    private String 보호자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
