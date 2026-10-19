package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManagerFactory;

import src.backend.boarding.entity.RiderStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.run.controller.DriverRunFixtures;
import testsupport.clock.FixedClock20300401Config;
import testsupport.redis.RedisFreeze;

/**
 * Redis 대체 위치의 {@code current_stop_name} — 같은 회차 상태에서 Redis 경로가 싣는 값과 같다(TECH_DECISIONS §14.2).
 * 세 회차를 출발 전 · 중간 정차지 도착 뒤 · 마지막 정차지 도착 뒤 상태로 두고, 위치 송신이 이벤트에 실은 이름(Redis 에 쓰이는 값)을
 * 기대값으로 삼아 Redis 를 얼린 뒤의 읽기와 견준다. 읽는 쪽은 보관소({@link RunPositionStore})와 이름을 그대로 내보내는 학부모
 * 버스 위치 API(§3.11)다. 관계자 · 메인 관리자 관제는 정차지 이름을 노선 테이블에서 직접 만들고, 비상 신고 위치 첨부는 좌표만 쓴다.
 *
 * <p>대체 조회문 수는 회차 수와 무관하다 — 회차 1개와 3개의 조회문 실행 횟수가 같다. 위치 송신이 커밋 뒤 리스너를 돌려야 해
 * {@code @Transactional} 을 쓰지 않고 만든 행을 직접 지운다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(FixedClock20300401Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RunPositionFallbackStopNameTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private RunPositionStore store;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CircuitBreakerRegistry breakerRegistry;
    @Autowired private CapturedEvents captured;

    private final List<Long> academyIds = new ArrayList<>();

    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }
    }

    /** 위치 송신이 이벤트에 실은 현재 정차지 이름 — 이 값이 Redis 에 쓰이는 값이다. */
    static class CapturedEvents {

        private final Map<Long, String> nameByRunId = new ConcurrentHashMap<>();

        @EventListener
        void onPosition(RunPositionReceivedEvent event) {
            nameByRunId.put(event.runId(), event.currentStopName() == null ? "" : event.currentStopName());
        }

        /** 이름이 없으면 {@code null}. */
        String nameOf(long runId) {
            String name = nameByRunId.get(runId);
            assertThat(name).as("회차 %d 의 위치 송신 이벤트".formatted(runId)).isNotNull();
            return name.isEmpty() ? null : name;
        }
    }

    private record Fleet(long academyId, long guardianAccountId, RunSeed a, RunSeed b, RunSeed c) {
        List<Long> runIds() {
            return List.of(a.runId(), b.runId(), c.runId());
        }
    }

    /** 회차 하나와 그 회차에 탄 학생 · 배정 기사. */
    private record RunSeed(long runId, long studentId, long driverAccountId) {
    }

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN (SELECT id FROM student WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM guardian WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("Redis 장애 중 대체 위치와 학부모 버스 위치의 current_stop_name 은 Redis 경로가 싣는 값과 같다")
    void 장애_중_대체_위치의_정차지_이름은_Redis_경로_값과_같다() throws Exception {
        Fleet fleet = 회차_세_개를_심고_위치를_보낸다();
        String before = captured.nameOf(fleet.a().runId());
        String middle = captured.nameOf(fleet.b().runId());
        String last = captured.nameOf(fleet.c().runId());
        assertThat(before).as("출발 전에는 도착한 정차지가 없어 이름이 없다").isNull();
        assertThat(middle).as("중간 정차지 도착 뒤 Redis 경로 이름").isNotNull();
        assertThat(last).as("마지막 정차지 도착 뒤 Redis 경로 이름").isNotNull().isNotEqualTo(middle);

        try (RedisFreeze ignored = RedisFreeze.start()) {
            assertThat(store.find(fleet.a().runId()).orElseThrow().currentStopName()).as("출발 전 대체 이름").isEqualTo(before);
            assertThat(store.find(fleet.b().runId()).orElseThrow().currentStopName()).as("중간 정차지 도착 뒤 대체 이름").isEqualTo(middle);
            assertThat(store.find(fleet.c().runId()).orElseThrow().currentStopName()).as("마지막 정차지 도착 뒤 대체 이름").isEqualTo(last);

            Map<Long, RunPositionRedisValue> all = store.findAll(fleet.runIds());
            assertThat(all.get(fleet.a().runId()).currentStopName()).as("findAll 출발 전").isEqualTo(before);
            assertThat(all.get(fleet.b().runId()).currentStopName()).as("findAll 중간 정차지 도착 뒤").isEqualTo(middle);
            assertThat(all.get(fleet.c().runId()).currentStopName()).as("findAll 마지막 정차지 도착 뒤").isEqualTo(last);

            assertThat(학부모_화면의_정차지_이름(fleet, fleet.a())).as("학부모 화면 출발 전").isEqualTo(before);
            assertThat(학부모_화면의_정차지_이름(fleet, fleet.b())).as("학부모 화면 중간 정차지 도착 뒤").isEqualTo(middle);
            assertThat(학부모_화면의_정차지_이름(fleet, fleet.c())).as("학부모 화면 마지막 정차지 도착 뒤").isEqualTo(last);
        }
    }

    @Test
    @DisplayName("Redis 가 살아 있고 키만 없을 때의 대체 위치도 Redis 경로가 싣는 값과 같은 정차지 이름을 낸다")
    void 키가_없을_때_대체_위치도_정차지_이름을_낸다() throws Exception {
        Fleet fleet = 회차_세_개를_심고_위치를_보낸다();
        String last = captured.nameOf(fleet.c().runId());
        awaitClosed(fleet.c().runId());
        redisTemplate.delete("run:%d:position".formatted(fleet.c().runId()));

        assertThat(store.find(fleet.c().runId()).orElseThrow().currentStopName()).as("키 없음 대체 이름").isEqualTo(last);
    }

    @Test
    @DisplayName("대체 위치를 읽는 SQL 문 수는 회차 1개와 3개가 같다")
    void 대체_조회문_수는_회차_수와_무관하다() throws Exception {
        Fleet fleet = 회차_세_개를_심고_위치를_보낸다();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        try (RedisFreeze ignored = RedisFreeze.start()) {
            store.find(fleet.a().runId()); // 서킷이 열리도록 한 번 부른다 — 이후 읽기는 Redis 를 기다리지 않는다
            long one = 문_수(statistics, () -> store.findAll(List.of(fleet.a().runId())));
            long three = 문_수(statistics, () -> assertThat(store.findAll(fleet.runIds())).hasSize(3));

            assertThat(one).as("회차 1개 읽기가 보낸 SQL 문 수").isPositive();
            assertThat(three).as("회차 3개 읽기가 보낸 SQL 문 수는 1개와 같다").isEqualTo(one);
        }
    }

    /** 읽기 하나가 DB 로 보낸 SQL 문 전부(어느 표든)의 개수 증가분. */
    private long 문_수(Statistics statistics, Runnable read) {
        long before = statistics.getPrepareStatementCount();
        read.run();
        return statistics.getPrepareStatementCount() - before;
    }

    private String 학부모_화면의_정차지_이름(Fleet fleet, RunSeed run) throws Exception {
        String body = mockMvc.perform(get("/api/v1/students/%d/bus-position".formatted(run.studentId()))
                        .header("Authorization", 토큰(tokenProvider, fleet.guardianAccountId(), fleet.academyId(), Role.PARENT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.get("lat")).as("대체 위치 좌표가 실린다").isNotNull();
        return (String) data.get("current_stop_name");
    }

    /**
     * 회차 셋 — A: 도착 전 · B: 정차지 1·2 도착(중간) · C: 정차지 1·2·3 도착(마지막). 각 회차에 학생 한 명이 타고 한 보호자가 셋 다
     * 자녀로 둔다. 위치는 Redis 가 살아 있을 때 한 번씩 보낸다(이벤트가 Redis 경로의 이름을 싣는다).
     */
    private Fleet 회차_세_개를_심고_위치를_보낸다() throws Exception {
        captured.nameByRunId.clear();
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        academyIds.add(academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        RunSeed a = 회차(fx, academyId, now, 0);
        RunSeed b = 회차(fx, academyId, now, 2);
        RunSeed c = 회차(fx, academyId, now, 3);
        long guardian = fx.guardianOf(academyId, a.studentId(), "보호자", now);
        fx.siblingOf(guardian, b.studentId(), now);
        fx.siblingOf(guardian, c.studentId(), now);
        for (RunSeed run : List.of(a, b, c)) {
            mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                            .header("Authorization", 토큰(tokenProvider, run.driverAccountId(), academyId, Role.DRIVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": 37.561000, "lng": 126.971000, "recorded_at": "%s"}
                                    """.formatted(now.minusSeconds(1))))
                    .andExpect(status().isNoContent());
        }
        return new Fleet(academyId, guardian, a, b, c);
    }

    /** 이동 중 회차 하나 — 정차지 3곳 + 학원 도착지, 앞에서부터 {@code arrivedCount} 곳이 도착 처리된 상태. */
    private RunSeed 회차(DriverRunFixtures fx, long academyId, OffsetDateTime now, int arrivedCount) {
        long runId = fx.confirmedRun(academyId, fx.bus(academyId), Direction.TO_ACADEMY, now, now.minusMinutes(30));
        fx.startRun(runId, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long firstStop = fx.stop(academyId, "37.560000", "126.970000");
        long[] runStopIds = {
                fx.runStopForStop(versionId, firstStop, 1, now),
                fx.runStopForStop(versionId, fx.stop(academyId, "37.561000", "126.971000"), 2, now.plusMinutes(5)),
                fx.runStopForStop(versionId, fx.stop(academyId, "37.562000", "126.972000"), 3, now.plusMinutes(10))};
        fx.runStopForDestination(versionId, 4);
        for (int i = 0; i < arrivedCount; i++) {
            jdbcTemplate.update("UPDATE run_stop SET arrived_at = ? WHERE id = ?", now.minusMinutes(10 - i), runStopIds[i]);
        }
        long driver = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        long student = fx.student(academyId, "학생" + runId);
        fx.rider(runId, student, firstStop, RiderStatus.BOARDED, now);
        return new RunSeed(runId, student, driver);
    }

    /** 앞 시험이 얼렸다 푼 Redis 의 서킷이 닫힐 때까지 읽어 본다 — 열린 채면 키 없음이 아니라 장애 대체가 돈다. */
    private void awaitClosed(long runId) {
        CircuitBreaker breaker = breakerRegistry.circuitBreaker("redis");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> {
            store.find(runId);
            return breaker.getState() == CircuitBreaker.State.CLOSED;
        });
    }
}
