package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.clock.SeedDateClockConfig;
import testsupport.redis.RedisFreeze;

/**
 * Redis 가 죽어도 위치 조회 3종은 {@code run_position} 최신 행으로 대체해 {@code 200} 을 낸다(W12-04 · BR-167,
 * TECH_DECISIONS §14.2). 관제는 회차가 몇 개든 대체 조회가 한 번이다.
 *
 * <p>Redis 장애는 이 시험 JVM 의 전용 Redis 컨테이너를 얼려 만든다({@link RedisFreeze}) — 명령 시간 상한(BR-166)
 * 뒤 실패한다. 시드 회차 R3(학원 1, moving)에 그 어떤 시험의 행보다 늦은 기록 2건을 심고 새 쪽 좌표가 나오는지
 * 본다. 요청이 만든 행을 커밋 없이 보려면 트랜잭션을 나눠야 해 {@code @Transactional} 을 쓰지 않고, 심은 행을
 * 직접 지운다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(SeedDateClockConfig.class)
class RunPositionFallbackTest {

    private static final long ACADEMY_A = 1L;

    private static final long RUN_MOVING_ID = 3L;

    private static final long SYSTEM_ADMIN_ACCOUNT = 1L;

    private static final long STAFF_ACCOUNT = 2L;

    private static final long GUARDIAN_ACCOUNT = 5L;

    private static final long STUDENT_ON_RUN_3 = 2L;

    /** 새 기록 — 옛 기록보다 기록 시각이 늦다. 시드·다른 시험의 행(현재 시각 근처)보다도 늦게 둔다. */
    private static final String NEW_LAT = "37.600001";

    private static final String NEW_LNG = "127.600001";

    private static final String OLD_LAT = "37.500001";

    private static final String OLD_LNG = "127.500001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    private final List<Long> positionIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        positionIds.forEach(id -> jdbcTemplate.update("DELETE FROM run_position WHERE id = ?", id));
        positionIds.clear();
    }

    @Test
    @DisplayName("BR-167 — Redis 장애 중 학부모 버스 위치는 DB 최신 행 좌표로 200")
    void Redis_장애_중_학부모_버스_위치는_DB_최신_행_좌표로_200() throws Exception {
        기록_두_건을_심는다();
        double fallbacksBefore = 대체_횟수();

        try (RedisFreeze ignored = RedisFreeze.start()) {
            mockMvc.perform(get("/api/v1/students/%d/bus-position".formatted(STUDENT_ON_RUN_3))
                            .header("Authorization", 토큰(GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lat").value(Double.parseDouble(NEW_LAT)))
                    .andExpect(jsonPath("$.data.lng").value(Double.parseDouble(NEW_LNG)));
        }

        assertThat(대체_횟수() - fallbacksBefore).as("schoolbus.position.fallback 증가분").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("BR-167 — Redis 장애 중 관계자 관제는 DB 최신 행 좌표로 200")
    void Redis_장애_중_관계자_관제는_DB_최신_행_좌표로_200() throws Exception {
        기록_두_건을_심는다();

        try (RedisFreeze ignored = RedisFreeze.start()) {
            mockMvc.perform(get("/api/v1/staff/runs/live")
                            .header("Authorization", 토큰(STAFF_ACCOUNT, ACADEMY_A, Role.STAFF)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.runs[?(@.run_id == 3)].position.lat")
                            .value(Double.parseDouble(NEW_LAT)))
                    .andExpect(jsonPath("$.data.runs[?(@.run_id == 3)].position.lng")
                            .value(Double.parseDouble(NEW_LNG)));
        }
    }

    /**
     * 메인 관리자 관제는 오늘 회차 전부(시드 학원 1 은 여러 건)를 그린다 — 회차마다 Redis 를 기다리거나 대체 조회를
     * 하면 회차 수만큼 곱해진다. 대체 조회문 실행 횟수가 1 이고 응답이 명령 시간 상한 한 번 남짓에 끝나는지 본다.
     */
    @Test
    @DisplayName("BR-167 — Redis 장애 중 메인 관리자 관제는 대체 조회 한 번으로 200")
    void Redis_장애_중_메인_관리자_관제는_대체_조회_한_번으로_200() throws Exception {
        기록_두_건을_심는다();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = 대체_조회_실행_횟수(statistics);

        long elapsedNanos;
        try (RedisFreeze ignored = RedisFreeze.start()) {
            long start = System.nanoTime();
            mockMvc.perform(get("/api/v1/admin/academies/%d/runs/live".formatted(ACADEMY_A))
                            .header("Authorization", 토큰(SYSTEM_ADMIN_ACCOUNT, null, Role.SYSTEM_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.runs.length()").value(org.hamcrest.Matchers.greaterThan(1)))
                    .andExpect(jsonPath("$.data.runs[?(@.run_id == 3)].position.lat")
                            .value(Double.parseDouble(NEW_LAT)));
            elapsedNanos = System.nanoTime() - start;
        }

        assertThat(대체_조회_실행_횟수(statistics) - before).as("회차 수와 무관하게 대체 조회문은 한 번").isEqualTo(1);
        assertThat(Duration.ofNanos(elapsedNanos)).as("회차마다 Redis 를 기다리지 않는다")
                .isLessThan(Duration.ofMillis(1500));
    }

    /** R3 에 옛 기록·새 기록을 심는다 — 둘 다 막 수신한 것으로 둬(2분 유실 판정 밖) 좌표가 응답에 실린다. */
    private void 기록_두_건을_심는다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        positionIds.add(심는다(OLD_LAT, OLD_LNG, now.plusHours(1), now));
        positionIds.add(심는다(NEW_LAT, NEW_LNG, now.plusHours(2), now));
    }

    private long 심는다(String lat, String lng, OffsetDateTime recordedAt, OffsetDateTime receivedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (?, ?::numeric, ?::numeric, ?, ?) RETURNING id""", Long.class,
                RUN_MOVING_ID, lat, lng, recordedAt, receivedAt);
    }

    private double 대체_횟수() {
        Counter counter = meterRegistry.find("schoolbus.position.fallback").counter();
        return counter == null ? 0.0d : counter.count();
    }

    /** 회차별 최신 행 조회문(DISTINCT ON)의 누적 실행 횟수 — 문장 원문으로 찾는다. */
    private long 대체_조회_실행_횟수(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(query -> query.toLowerCase(Locale.ROOT).contains("distinct on"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }

    private String 토큰(long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
