package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.common.SeedFixtures;
import src.backend.global.security.JwtTokenProvider;

/**
 * 자녀·본인 당일 회차 목록 API(P-04 · S-01, API_SPEC §3.5, 목표 5).
 *
 * <p>student1(academy1)이 소속된 회차 3건(idle·confirmed·moving)을 한 번에 검증한다 — 시드가
 * 이미 세 상태를 전부 갖추고 있어(run1 idle · run2 confirmed · run3 moving · run4 finished 는
 * student1 소속 아님) 새 픽스처를 만들지 않는다.
 *
 * <ul>
 *   <li>run3(moving) — {@code run_rider} 행이 {@code status='absent'} 라 {@code riding} 기본값
 *       (참가 의사 없음 → 아직 안 됨)과 무관하게 {@code rider_status} 는 그 행을 그대로 따라야
 *       한다 — {@code StudentRunsQueryService.toItem} 이 {@code context.rider()} 를 우선하는지
 *       가르는 유일한 경우다</li>
 *   <li>run2(confirmed) — {@code boarding_intent} 행이 있어(riding=true, change_used_count=0)
 *       기본값이 아니라 그 행의 값을 그대로 반영해야 한다</li>
 *   <li>run1(idle) — {@code boarding_intent} 행이 없어 기본값(riding=true, change_quota_left=1)
 *       이어야 한다</li>
 * </ul>
 *
 * <p>{@link SeedDateClockConfig} — 시드 회차의 {@code service_date} 를 실제로 읽어 그 날짜로
 * {@link Clock} 을 이동시킨다({@code StudentRouteControllerTest} 와 같은 설계).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 아래 시드 복원이 이 클래스 밖으로 새지 않게 한다 — 복원 훅 주석 참고.
class StudentRunsControllerTest {

    private static final String RUNS = "/api/v1/students/%d/runs";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 시드의 {@code idle} 회차를 그 상태로 되돌린다.
     *
     * <p>이 클래스의 전제는 "시드가 idle·confirmed·moving 세 상태를 갖췄다" 인데, 확정 배치·강제 확정을
     * 검사하는 다른 클래스가 <b>같은 행을 confirmed 로 바꾼다</b>. 그래서 단독 실행은 통과하고 전체
     * 실행에서만 {@code items[2].run_status} 가 {@code confirmed} 로 나와 실패했다(2026-09-09 실측).
     * 전제를 매 시험 앞에서 복원해 실행 순서에 기대지 않게 한다 — {@code confirmed} 응답 필드도
     * {@code status != idle} 에서 파생되므로 이 한 줄이 두 단언을 함께 되돌린다.
     *
     * <p>⚠ 클래스에 {@code @Transactional} 을 단 이유가 이 복원이다. 커밋해 버리면 <b>확정 배치
     * 시험이 깨진다</b> — 그 시험은 한 틱이 정확히 {@code BATCH_SIZE} 건을 집는지 보는데, 되살아난
     * idle 회차가 후보 한 자리를 차지해 자기 회차가 49건만 확정된다(2026-09-09 실측). 롤백으로
     * 이 클래스 밖에 흔적을 남기지 않는다.
     */
    @BeforeEach
    void 시드_idle_회차를_되돌린다() {
        jdbcTemplate.update("UPDATE run SET status = 'idle', confirmed_at = NULL WHERE id = ?",
                Long.parseLong(SeedFixtures.RUN_IDLE_ID));
    }

    private static final long ACADEMY_A = 1L;

    /** student1·student2 의 보호자. */
    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    /** student4(academy1) 본인 계정 — 이 코드베이스 최초의 학생 본인 접근 경로 시험 대상. */
    private static final long STUDENT_4_SELF_ACCOUNT = 10L;

    /** student6(academy2) 본인 계정. */
    private static final long STUDENT_6_SELF_ACCOUNT = 12L;

    private static final long STUDENT_1_ID = 1L;

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

    /** student1 이 속한 회차 3건을 출발 시각 순(moving → confirmed → idle)으로 반환한다. */
    @Test
    void 부모가_연결된_자녀의_당일_회차_목록을_출발시각_순으로_받는다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].run_id").value(3))
                .andExpect(jsonPath("$.data.items[0].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.items[0].bus_no").value("2호차"))
                .andExpect(jsonPath("$.data.items[0].run_status").value("moving"))
                .andExpect(jsonPath("$.data.items[0].confirmed").value(true))
                .andExpect(jsonPath("$.data.items[0].riding").value(true))
                .andExpect(jsonPath("$.data.items[0].rider_status").value("absent"))
                .andExpect(jsonPath("$.data.items[0].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[0].stop.name").value("중앙로 스타빌딩 앞"))
                .andExpect(jsonPath("$.data.items[0].change_quota_left").value(1))
                .andExpect(jsonPath("$.data.items[1].run_id").value(2))
                .andExpect(jsonPath("$.data.items[1].direction").value("from_academy"))
                .andExpect(jsonPath("$.data.items[1].bus_no").value("1호차"))
                .andExpect(jsonPath("$.data.items[1].run_status").value("confirmed"))
                .andExpect(jsonPath("$.data.items[1].confirmed").value(true))
                .andExpect(jsonPath("$.data.items[1].riding").value(true))
                .andExpect(jsonPath("$.data.items[1].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[1].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[1].change_quota_left").value(1))
                .andExpect(jsonPath("$.data.items[2].run_id").value(1))
                .andExpect(jsonPath("$.data.items[2].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.items[2].bus_no").value("1호차"))
                .andExpect(jsonPath("$.data.items[2].run_status").value("idle"))
                .andExpect(jsonPath("$.data.items[2].confirmed").value(false))
                .andExpect(jsonPath("$.data.items[2].riding").value(true))
                .andExpect(jsonPath("$.data.items[2].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[2].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[2].change_quota_left").value(1));
    }

    /** {@code date} 를 생략하면 당일이다 — 시드가 전부 {@code CURRENT_DATE} 라 쿼리 파라미터 없이도 같은 3건이 나와야 한다. */
    @Test
    void date_파라미터를_생략하면_당일_기준이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    /** 연결이 없는(대기 중인 요청뿐인) 자녀는 403 이다 — S1 의 보호자가 아니라 계정5는 student5 에 활성 연결이 없다. */
    @Test
    void 연결_부재_자녀의_회차_조회는_403_이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_5_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 학생 본인은 자기 회차를 볼 수 있다 — 이 코드베이스 최초의 학생 본인 접근 경로. */
    @Test
    void 학생_본인은_자기_회차_목록을_받는다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_4_ID))
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(STUDENT_4_SELF_ACCOUNT,
                                ACADEMY_A, Role.STUDENT, AccountStatus.ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].run_id").value(2))
                .andExpect(jsonPath("$.data.items[0].riding").value(false))
                .andExpect(jsonPath("$.data.items[0].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[0].change_quota_left").value(0));
    }

    /** 본인이 아닌 학생의 회차는 학생 role 이어도 403 이다 — §3 도입부 "본인 아닌 학생". */
    @Test
    void 학생이_본인_아닌_학생의_회차를_조회하면_403_이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID))
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(STUDENT_4_SELF_ACCOUNT,
                                ACADEMY_A, Role.STUDENT, AccountStatus.ACTIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 다른 학원 학생이 본인이어도 경로의 studentId 와 다르면 403 이다(academy2 계정 → academy1 학생). */
    @Test
    void 타_학원_학생_본인_계정이_다른_학생을_조회하면_403_이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID))
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(STUDENT_6_SELF_ACCOUNT,
                                2L, Role.STUDENT, AccountStatus.ACTIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** STUDENT_READ_BASIC 을 보유해도(기사·동승자·직원) §3.5 는 학부모·학생만 허용한다. */
    @Test
    void 기사는_회차_목록을_조회할_수_없다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID))
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(13L, ACADEMY_A,
                                Role.DRIVER, AccountStatus.ACTIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private String 토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
