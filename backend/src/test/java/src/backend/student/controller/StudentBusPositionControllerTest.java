package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.redis.IsolatedRedisTestBase;

/**
 * 학부모 앱의 실시간 버스 위치 API(LOC-02, API_SPEC §3.11, Ruling 208) — 목표 9·11.
 *
 * <p>회차 R3(academy A, moving)가 세 갈래를 동시에 갖는다 — student2(boarded, 위치 정상),
 * student1(absent, 위치 항상 부재), 그리고 이 시험이 Redis 에 직접 쓰는 위치 신호의 신선도. 이
 * 셋을 한 회차로 몰아 쓰는 이유는 "그 학생의 회차가 아니라 회차 자체의 상태" 로 판정이 갈리는지,
 * 아니면 회차와 무관한 학생별 예외인지를 같은 조건에서 갈라 보기 위함이다.
 *
 * <p>Redis 키는 회차 단위({@code run:{runId}:position})라 학생과 무관하다 — 그래서 신선도(fresh ·
 * stale)를 검사하는 두 시험은 매번 자기 값으로 덮어써야 하고, 순서에 의존하면 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudentBusPositionControllerTest extends IsolatedRedisTestBase {

    private static final String POSITION = "/api/v1/students/%d/bus-position";

    private static final long ACADEMY_A = 1L;

    /** 형제 S1·S2 의 보호자(parentA1) — R3 의 두 갈래(boarded·absent)를 모두 관측할 수 있다. */
    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    /** S4 의 보호자(parentA2) — S4 는 오늘 R2(confirmed) 하나에만 속해 미운행 갈래를 볼 수 있다. */
    private static final long STUDENT_4_GUARDIAN_ACCOUNT = 6L;

    /** S5 의 보호자(parentA3) — S1 과는 연결이 부재해 403 대조군이다. */
    private static final long UNLINKED_GUARDIAN_ACCOUNT = 7L;

    private static final long SIBLING_1_ID = 1L;

    private static final long SIBLING_2_ID = 2L;

    private static final long STUDENT_4_ID = 4L;

    /** R3 — 학원 A, moving. student2(boarded)·student1(absent) 이 함께 속한 회차다. */
    private static final long RUN_MOVING_ID = 3L;

    /** R2 — 학원 A, confirmed(운행 전). student4 가 속한 회차다. */
    private static final long RUN_CONFIRMED_ID = 2L;

    private static final String POSITION_KEY = "run:%d:position".formatted(RUN_MOVING_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 신호가 살아 있으면(§3.11) 좌표가 담기고 last_seen_at 은 비어야 한다. */
    @Test
    void 운행중_회차의_신선한_위치는_좌표를_반환한다() throws Exception {
        OffsetDateTime received = OffsetDateTime.now();
        위치를_기록한다(received);

        mockMvc.perform(get(POSITION.formatted(SIBLING_2_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_status").value("moving"))
                .andExpect(jsonPath("$.data.lat").value(37.400000))
                .andExpect(jsonPath("$.data.lng").value(127.400000))
                .andExpect(jsonPath("$.data.received_at").exists())
                .andExpect(jsonPath("$.data.last_seen_at").doesNotExist());
    }

    /**
     * 마지막 수신 후 2분(Ruling 208)이 지나면 좌표 대신 last_seen_at 만 채운다(목표 9).
     *
     * <p>{@code received_at} 이 비고 {@code last_seen_at} 이 채워지는 것까지 함께 본다 — 좌표만 없애고
     * {@code received_at} 을 그대로 두면 "언제 신호가 왔는지" 와 "언제까지 유효했는지" 가 뒤섞인다.
     */
    @Test
    void 마지막_수신_후_2분이_지나면_좌표_대신_last_seen_at_을_반환한다() throws Exception {
        OffsetDateTime staleReceived = OffsetDateTime.now().minusMinutes(3);
        위치를_기록한다(staleReceived);

        mockMvc.perform(get(POSITION.formatted(SIBLING_2_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lat").doesNotExist())
                .andExpect(jsonPath("$.data.lng").doesNotExist())
                .andExpect(jsonPath("$.data.received_at").doesNotExist())
                .andExpect(jsonPath("$.data.last_seen_at").exists());
    }

    /**
     * 당일 결석(absent)이면 회차가 moving 이어도 위치가 부재하다(§3.11) — student1 은 R3 에서
     * absent 상태다. Redis 에 신선한 값을 심어 둬도(운행중_회차의_신선한_위치 시험과 같은 키) 좌표가
     * 나가지 않는 것까지 봐야, "회차 상태만 보고 학생별 예외를 빼먹은 구현" 을 잡는다.
     */
    @Test
    void 당일_결석이면_운행중이어도_좌표가_부재하다() throws Exception {
        위치를_기록한다(OffsetDateTime.now());

        mockMvc.perform(get(POSITION.formatted(SIBLING_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_status").value("moving"))
                .andExpect(jsonPath("$.data.lat").doesNotExist())
                .andExpect(jsonPath("$.data.lng").doesNotExist());
    }

    /**
     * 회차가 moving 이 아니면(§3.11) 위치 신호와 무관하게 좌표가 부재하다 — student4 는 오늘
     * R2(confirmed) 뿐이다. Redis 에 신선한 값을 일부러 심어 둔다 — 안 심으면 "캐시가 비어서
     * 부재" 와 "moving 이 아니라서 부재" 가 같은 결과로 겹쳐, moving 검사를 지워도 이 시험이
     * 못 잡는다.
     */
    @Test
    void 운행중이_아닌_회차는_좌표_없이_상태만_반환한다() throws Exception {
        String key = "run:%d:position".formatted(RUN_CONFIRMED_ID);
        String json = """
                {"lat":37.400000,"lng":127.400000,"recordedAt":"%1$s","receivedAt":"%1$s","currentStopName":"중앙 집결지"}"""
                .formatted(OffsetDateTime.now());
        stringRedisTemplate.opsForValue().set(key, json);

        mockMvc.perform(get(POSITION.formatted(STUDENT_4_ID)).header("Authorization", 토큰(STUDENT_4_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_status").value("confirmed"))
                .andExpect(jsonPath("$.data.lat").doesNotExist())
                .andExpect(jsonPath("$.data.lng").doesNotExist());
    }

    /** 연결 부재 자녀는 위치 조회도 403 이다(목표 11) — S5 의 보호자가 S1 을 조회한다. */
    @Test
    void 연결_부재_자녀의_위치_조회는_403_이다() throws Exception {
        mockMvc.perform(get(POSITION.formatted(SIBLING_1_ID)).header("Authorization", 토큰(UNLINKED_GUARDIAN_ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private void 위치를_기록한다(OffsetDateTime receivedAt) {
        String json = """
                {"lat":37.400000,"lng":127.400000,"recordedAt":"%s","receivedAt":"%s","currentStopName":"한빛아파트 정문"}"""
                .formatted(receivedAt, receivedAt);
        stringRedisTemplate.opsForValue().set(POSITION_KEY, json);
    }

    private String 토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
