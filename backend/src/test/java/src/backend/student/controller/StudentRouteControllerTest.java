package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학부모 앱의 자녀 노선 조회 API(LOC-03, API_SPEC §3.10) — 목표 12, "표시 범위" 절단.
 *
 * <p>R3(moving, route_version 3, to_academy)의 정차 순서는 stop2(seq1) → stop3(seq2) →
 * stop1(seq3) → stop4(seq4) 다(V2 시드, waypoint 는 stop_id 가 없어 항상 제외). 이 순서 하나로
 * 절단의 세 경계를 모두 실측할 수 있다 — 새 픽스처를 만들지 않고 기존 시드 조합을 그대로 쓴다.
 *
 * <ul>
 *   <li>student2 = 자기 정차지가 맨 앞(seq1) — 앞에 볼 것이 없다</li>
 *   <li>student1 = 자기 정차지 앞에 정확히 2개(seq3) — 절단이 없어도 우연히 맞는 경계다</li>
 *   <li>student5 = 자기 정차지 앞에 3개가 있는데(seq4) 2개로 잘려야 한다 — 절단이 실제로
 *       동작하는지를 가르는 유일한 경우다. 이 경우가 없으면 "그냥 전체를 반환" 해도 다른
 *       두 시험은 통과한다</li>
 * </ul>
 *
 * <p><b>Ruling 288(목표 1)</b> — {@code stops[]} 는 등원·하원 두 방향 모두 하차지(도착지)가
 * 빠져 있었다. 학원은 {@code run_stop} 행이 없어(ERD.md) windowed 파이프라인만으로는 나오지
 * 않으므로, {@code Academy} 엔티티에서 합성한 항목을 방향별로 끝(등원)·앞(하원)에 덧붙인다. R3
 * (to_academy) 세 시험이 "끝에 학원이 붙는다" 를, R2(confirmed, from_academy) 가 "앞에 학원이
 * 붙는다" 를 각각 검증한다.
 *
 * <p>{@link SeedDateClockConfig} — 시드 회차의 {@code service_date} 를 실제로 읽어 그 날짜로
 * {@link Clock} 을 이동시킨다(Phase 10 T4 재수정, {@code StudentBusPositionControllerTest} 와 같은
 * 설계). 이 시험은 상대 시각을 직접 다루지 않아 얼린 Clock 도 무방하지만, 두 클래스가 같은
 * 원인(시드의 오늘 회차 조회)을 공유하므로 같은 해법을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudentRouteControllerTest {

    private static final String ROUTE = "/api/v1/students/%d/route";

    private static final long ACADEMY_A = 1L;

    /** 형제 S1·S2 의 보호자 — student2(맨 앞) · student1(정확히 2개 앞) 을 함께 관측한다. */
    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    /** S5 의 보호자 — student5(3개 중 2개로 절단) 를 관측하고, student1 과는 연결이 부재해 403 대조군이다. */
    private static final long STUDENT_5_GUARDIAN_ACCOUNT = 7L;

    /** G2 — student3·student4 의 보호자. student4(R2, from_academy) 로 "학원이 앞에 붙는다" 를 본다. */
    private static final long STUDENT_4_GUARDIAN_ACCOUNT = 6L;

    private static final long STUDENT_1_ID = 1L;

    private static final long STUDENT_2_ID = 2L;

    private static final long STUDENT_4_ID = 4L;

    private static final long STUDENT_5_ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 시드 회차의 {@code service_date} 를 읽어 그 날짜로 Clock 을 이동시킨다 — 클래스 자바독 참고. */
    @TestConfiguration
    static class SeedDateClockConfig {

        @Bean
        @Primary
        Clock seedDateClock(JdbcTemplate jdbcTemplate) {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            Clock base = Clock.system(seoul);
            LocalDate seedDate = jdbcTemplate.queryForObject("SELECT MIN(service_date) FROM run", LocalDate.class);
            long offsetDays = ChronoUnit.DAYS.between(LocalDate.now(base), seedDate);
            return Clock.offset(base, Duration.ofDays(offsetDays));
        }
    }

    /**
     * 자기 정차지가 노선의 맨 앞이면 앞에 보여줄 정차지는 없지만, R3 는 등원(to_academy)이라
     * 하차지인 학원이 끝에 하나 더 붙는다(Ruling 288 — 목표 1).
     */
    @Test
    void 정차지가_맨_앞이면_자기_자신과_학원만_반환한다() throws Exception {
        mockMvc.perform(get(ROUTE.formatted(STUDENT_2_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bus_no").value("2호차"))
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.driver.name").value("오기사"))
                .andExpect(jsonPath("$.data.my_stop_id").value(2))
                .andExpect(jsonPath("$.data.stops.length()").value(2))
                .andExpect(jsonPath("$.data.stops[0].stop_id").value(2))
                .andExpect(jsonPath("$.data.stops[0].name").value("한빛아파트 정문"))
                .andExpect(jsonPath("$.data.stops[1].stop_id").isEmpty())
                .andExpect(jsonPath("$.data.stops[1].name").value("바래다학원 A"))
                .andExpect(jsonPath("$.data.stops[1].lat").value(37.497942));
    }

    /**
     * 앞선 정차지가 정확히 2개면 절단 여부와 무관하게 결과가 같다 — 경계값이지 절단의 증거는 아니다.
     * R3 는 등원이라 끝에 학원(하차지)이 하나 더 붙는다(목표 1).
     */
    @Test
    void 앞선_정차지가_정확히_2개면_전부_반환하고_학원이_끝에_붙는다() throws Exception {
        mockMvc.perform(get(ROUTE.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my_stop_id").value(1))
                .andExpect(jsonPath("$.data.stops.length()").value(4))
                .andExpect(jsonPath("$.data.stops[0].stop_id").value(2))
                .andExpect(jsonPath("$.data.stops[1].stop_id").value(3))
                .andExpect(jsonPath("$.data.stops[2].stop_id").value(1))
                .andExpect(jsonPath("$.data.stops[3].stop_id").isEmpty())
                .andExpect(jsonPath("$.data.stops[3].name").value("바래다학원 A"));
    }

    /**
     * 앞선 정차지가 3개(stop2·stop3·stop1) 있어도 가장 가까운 2개(stop3·stop1)만 남기고 stop2 는
     * 잘라낸다(목표 12) — 절단이 실제로 동작함을 보이는 유일한 시험이다. R3 는 등원이라 학원이
     * 끝에 붙는다(목표 1) — 절단된 창 뒤에 붙는 것까지 함께 확인한다.
     */
    @Test
    void 앞선_정차지가_3개_이상이면_가까운_2개로_절단하고_학원이_끝에_붙는다() throws Exception {
        mockMvc.perform(get(ROUTE.formatted(STUDENT_5_ID)).header("Authorization", 토큰(STUDENT_5_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my_stop_id").value(4))
                .andExpect(jsonPath("$.data.stops.length()").value(4))
                .andExpect(jsonPath("$.data.stops[0].stop_id").value(3))
                .andExpect(jsonPath("$.data.stops[1].stop_id").value(1))
                .andExpect(jsonPath("$.data.stops[2].stop_id").value(4))
                .andExpect(jsonPath("$.data.stops[3].stop_id").isEmpty())
                .andExpect(jsonPath("$.data.stops[3].name").value("바래다학원 A"));
    }

    /**
     * R2(id=2) 는 하원(from_academy) 확정 회차 — 학원이 승차지라 앞에 붙는다(목표 1). student4 는
     * v2(S4 재배치로 stop3 추가)의 seq2(stop3) 라 자기 앞에 stop1(seq1) 하나만 있다.
     */
    @Test
    void 하원_회차는_학원이_앞에_붙는다() throws Exception {
        mockMvc.perform(get(ROUTE.formatted(STUDENT_4_ID)).header("Authorization", 토큰(STUDENT_4_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my_stop_id").value(3))
                .andExpect(jsonPath("$.data.stops.length()").value(3))
                .andExpect(jsonPath("$.data.stops[0].stop_id").isEmpty())
                .andExpect(jsonPath("$.data.stops[0].name").value("바래다학원 A"))
                .andExpect(jsonPath("$.data.stops[1].stop_id").value(1))
                .andExpect(jsonPath("$.data.stops[2].stop_id").value(3));
    }

    /** 연결 부재 자녀는 노선 조회도 403 이다 — S5 의 보호자가 S1 을 조회한다. */
    @Test
    void 연결_부재_자녀의_노선_조회는_403_이다() throws Exception {
        mockMvc.perform(get(ROUTE.formatted(STUDENT_1_ID)).header("Authorization", 토큰(STUDENT_5_GUARDIAN_ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private String 토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
