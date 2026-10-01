package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.common.SeedFixtures;
import src.backend.global.security.JwtTokenProvider;
import testsupport.clock.SeedDateClockConfig;

/**
 * 자녀·본인 당일 회차 목록 API(P-04 · S-01, API_SPEC §3.5, 목표 5).
 *
 * <p>student1(academy1)이 소속된 회차 5건(idle 2건·confirmed 2건·moving)을 한 번에 검증한다 — 시드가
 * 이미 세 상태를 전부 갖추고 있어(run1 idle · run2 confirmed · run3 moving · run4 finished 는
 * student1 소속 아님) 새 픽스처를 만들지 않는다. run6(idle) 은 §5.7 검증용으로 추가된 회차인데,
 * run1 과 같은 학원·버스·방향이라 같은 고정 노선(route id=1)에 걸려 {@code matchesFixedRoute} 가
 * student1 을 그대로 소속시킨다 — 시드에 노선이 그 하나뿐이라 우연이 아니라 필연이다. 그래서 이
 * 목록에도 네 번째 항목으로 나와야 정확하다(2026-09-12 실측 — run6 추가 후 3→4 로 개정). run8
 * (confirmed) 은 R14-T3 가 경유지 계약 검사 재료로 추가한 전용 회차(docs/archive/rounds/be-rounds-r5-r14.md §8.19)인데, academy·bus·
 * direction 이 run1·run6 과 같아 같은 고정 노선에 걸리고 명단에도 student1 이 들어 있어 이 목록에
 * 다섯 번째로 실린다(2026-09-19 실측 — run8 추가 후 4→5 로 개정, R14-T3 후속).
 *
 * <ul>
 *   <li>run3(moving) — {@code run_rider} 행이 {@code status='absent'} 라 {@code riding} 기본값
 *       (참가 의사 없음 → 아직 안 됨)과 무관하게 {@code rider_status} 는 그 행을 그대로 따라야
 *       한다 — {@code StudentRunsQueryService.toItem} 이 {@code context.rider()} 를 우선하는지
 *       가르는 유일한 경우다</li>
 *   <li>run2(confirmed) — {@code boarding_intent} 행이 있어(riding=true, change_used_count=0)
 *       기본값이 아니라 그 행의 값을 그대로 반영해야 한다</li>
 *   <li>run1·run6(idle) — {@code boarding_intent} 행이 없어 기본값(riding=true,
 *       change_quota_left=1)이어야 한다. 둘 다 route id=1 을 공유해 정차지도 stop1 로 같다</li>
 *   <li>run8(confirmed) — run1·run6 과 마찬가지로 {@code boarding_intent} 행이 없어 기본값이다.
 *       run2 와 상태(confirmed)는 같지만 {@code boarding_intent} 유무로 갈리는 예다</li>
 * </ul>
 *
 * <p>{@link SeedDateClockConfig} — 시드 회차의 {@code service_date} 를 실제로 읽어 그 날짜로
 * {@link Clock} 을 이동시킨다({@code StudentRouteControllerTest} 와 같은 설계).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 아래 시드 복원이 이 클래스 밖으로 새지 않게 한다 — 복원 훅 주석 참고.
@Import(SeedDateClockConfig.class)
class StudentRunsControllerTest {

    private static final String RUNS = "/api/v1/students/%d/runs";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 시드의 {@code idle} 회차 2건(run1·run6)을 그 상태로 되돌린다.
     *
     * <p>이 클래스의 전제는 "시드가 idle·confirmed·moving 상태를 갖췄다" 인데, 확정 배치·강제 확정을
     * 검사하는 다른 클래스가 <b>같은 행을 confirmed 로 바꾼다</b>. 그래서 단독 실행은 통과하고 전체
     * 실행에서만 {@code items[2].run_status} 가 {@code confirmed} 로 나와 실패했다(2026-09-09 실측).
     * 전제를 매 시험 앞에서 복원해 실행 순서에 기대지 않게 한다 — {@code confirmed} 응답 필드도
     * {@code status != idle} 에서 파생되므로 이 한 줄이 두 단언을 함께 되돌린다.
     *
     * <p>run6 은 §5.7(강제 추가) 검증용으로 추가된 idle 회차인데(Run6 시드 주석 참고), 같은 이유로
     * §5.7 강제 추가·확정 관련 시험이 이 행을 {@code confirmed} 로 남겨 둔 채 끝날 수 있다(2026-09-12
     * 전체 실행 실측 — DB 재구성 없이 오래 떠 있던 세션에서 run6 이 {@code confirmed} 로 관측됨).
     * run1 과 같은 이유로 여기서 함께 되돌린다.
     *
     * <p>⚠ 클래스에 {@code @Transactional} 을 단 이유가 이 복원이다. 커밋해 버리면 <b>확정 배치
     * 시험이 깨진다</b> — 그 시험은 한 틱이 정확히 {@code BATCH_SIZE} 건을 집는지 보는데, 되살아난
     * idle 회차가 후보 한 자리를 차지해 자기 회차가 49건만 확정된다(2026-09-09 실측). 롤백으로
     * 이 클래스 밖에 흔적을 남기지 않는다.
     */
    @BeforeEach
    void 시드_idle_회차를_되돌린다() {
        jdbcTemplate.update("UPDATE run SET status = 'idle', confirmed_at = NULL WHERE id IN (?, ?)",
                Long.parseLong(SeedFixtures.RUN_IDLE_ID), 6L);
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

    /** student1 이 속한 회차 5건을 출발 시각 순(moving → confirmed run2 → confirmed run8 → idle run1 → idle run6)으로 반환한다. */
    @Test
    void 부모가_연결된_자녀의_당일_회차_목록을_출발시각_순으로_받는다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
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
                .andExpect(jsonPath("$.data.items[2].run_id").value(8))
                .andExpect(jsonPath("$.data.items[2].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.items[2].bus_no").value("1호차"))
                .andExpect(jsonPath("$.data.items[2].run_status").value("confirmed"))
                .andExpect(jsonPath("$.data.items[2].confirmed").value(true))
                .andExpect(jsonPath("$.data.items[2].riding").value(true))
                .andExpect(jsonPath("$.data.items[2].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[2].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[2].change_quota_left").value(1))
                .andExpect(jsonPath("$.data.items[3].run_id").value(1))
                .andExpect(jsonPath("$.data.items[3].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.items[3].bus_no").value("1호차"))
                .andExpect(jsonPath("$.data.items[3].run_status").value("idle"))
                .andExpect(jsonPath("$.data.items[3].confirmed").value(false))
                .andExpect(jsonPath("$.data.items[3].riding").value(true))
                .andExpect(jsonPath("$.data.items[3].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[3].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[3].change_quota_left").value(1))
                .andExpect(jsonPath("$.data.items[4].run_id").value(6))
                .andExpect(jsonPath("$.data.items[4].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.items[4].bus_no").value("1호차"))
                .andExpect(jsonPath("$.data.items[4].run_status").value("idle"))
                .andExpect(jsonPath("$.data.items[4].confirmed").value(false))
                .andExpect(jsonPath("$.data.items[4].riding").value(true))
                .andExpect(jsonPath("$.data.items[4].rider_status").value("waiting"))
                .andExpect(jsonPath("$.data.items[4].stop.stop_id").value(1))
                .andExpect(jsonPath("$.data.items[4].change_quota_left").value(1));
    }

    /** {@code date} 를 생략하면 당일이다 — 시드가 전부 {@code CURRENT_DATE} 라 쿼리 파라미터 없이도 같은 5건이 나와야 한다. */
    @Test
    void date_파라미터를_생략하면_당일_기준이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5));
    }

    /** 연결이 없는(대기 중인 요청뿐인) 자녀는 403 이다 — S1 의 보호자가 아니라 계정5는 student5 에 활성 연결이 없다. */
    @Test
    void 연결_부재_자녀의_회차_조회는_403_이다() throws Exception {
        mockMvc.perform(get(RUNS.formatted(STUDENT_5_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /**
     * 연결은 살아 있는데 <b>퇴원 처리된</b> 자녀는 404 다({@code LinkedChildLookup} 의 판정 순서).
     *
     * <p>403(연결 부재)과 갈라야 하는 이유는 클라이언트의 다음 행동이 다르기 때문이다 — 403 은
     * 연결을 신청할 자리이고, 404 는 그 학생이 이 학원에 더는 없다는 뜻이다. 퇴원생을 계속 볼 수
     * 있으면 명단에서 빠진 학생이 노선 계산의 입력으로 되살아난다.
     *
     * <p>시드에는 "연결은 있는데 퇴원한" 행이 없어 여기서 직접 만든다. 이 클래스는
     * {@code @Transactional} 이라 이 수정이 다른 시험으로 새지 않는다.
     */
    @Test
    void 퇴원_처리된_자녀의_회차_조회는_404_STUDENT_NOT_FOUND_이다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", STUDENT_1_ID);

        mockMvc.perform(get(RUNS.formatted(STUDENT_1_ID)).header("Authorization", 토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
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

    /**
     * 퇴원한 학생 본인 계정은 회차·노선·버스 위치 조회를 모두 {@code 404 STUDENT_NOT_FOUND} 로 받는다(BR-212) —
     * 학부모 경로({@code LinkedChildLookup})가 퇴원 자녀를 막는 것과 같은 코드다. 퇴원 처리는 학생 본인 계정을
     * 건드리지 않아 이 판정이 없으면 명단에서 빠진 학생이 학원 버스의 실시간 위치를 계속 받는다.
     */
    @Test
    void 퇴원한_학생_본인은_회차_노선_버스위치_조회가_404_STUDENT_NOT_FOUND_이다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", STUDENT_4_ID);
        String selfToken = "Bearer " + tokenProvider.createAccessToken(STUDENT_4_SELF_ACCOUNT, ACADEMY_A,
                Role.STUDENT, AccountStatus.ACTIVE);

        for (String path : new String[] {RUNS, "/api/v1/students/%d/route", "/api/v1/students/%d/bus-position"}) {
            mockMvc.perform(get(path.formatted(STUDENT_4_ID)).header("Authorization", selfToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
        }
    }

    private String 토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}
