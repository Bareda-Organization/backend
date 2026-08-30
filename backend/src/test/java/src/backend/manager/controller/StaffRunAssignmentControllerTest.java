package src.backend.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.14 {@code PATCH /staff/runs/{runId}/assignment} — MGR-05 배치 · MGR-06 충돌 <b>경고</b>
 * (Ruling 152: 차단이 아니다 · Ruling 165: 판정 4항).
 *
 * <p><b>이 클래스의 최우선 단언은 "저장은 되고 경고가 실린다"</b>다. 저장 성공(200)만 보면 충돌 판정을
 * 아예 안 하는 구현이 통과하고, 경고만 보면 차단하는 구현과 구별되지 않는다. 반대편으로
 * {@link #겹치지_않는_배치에서는_warnings_가_빈_배열이다()} 가 <b>항상 경고를 다는 구현</b>을 막는다 —
 * 경계 양쪽이 함께 있어야 이 기능이 검사된다.
 *
 * <p>경고 2종이 <b>서로 독립</b>인 것도 별도로 잡는다
 * ({@link #근무_시간_미기재_매니저도_중복_배치_경고를_함께_받는다()}) — 하나로 묶은 구현에서는
 * {@code MANAGER_DOUBLE_BOOKED} 가 근무 시간 미기재 매니저에서 조용히 사라진다(Ruling 165 ③).
 *
 * <p><b>점 판정을 구간으로 올렸다</b>(Ruling 165 ② 재판정 · Phase 7 목표 12) — {@code
 * est_duration_min} 이 채워진 회차는 출발~종료 구간 전체를 근무 구간과 대조한다
 * ({@link #운행_시간이_근무_구간을_넘기면_구간_판정으로_경고가_난다()}). 값이 비어 있으면(확정 전
 * 수동 배치의 정상 상태) 기존처럼 출발 시각 하나로 접혀 점 판정과 같다 — {@code
 * AssignmentConflictDetector#windowEnd} 참조.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRunAssignmentControllerTest {

    /** 시드의 학원 A 관계자({@code staffA})와 그 학원. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 남의 학원 회차를 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /** 시드 학원 A 의 1·2호차. 두 회차를 같은 시각에 두려면 차량이 둘 필요하다. */
    private static final long BUS_A1_ID = 1L;

    private static final long BUS_A2_ID = 2L;

    private static final long BUS_B_ID = 3L;

    /** 시드 매니저 — 근무 시간이 서로 다르게 배분돼 있다(V2 주석 참조). */
    private static final long 강기사_종일 = 1L;

    private static final long 오기사_오전만 = 2L;

    private static final long 서동승_종일 = 3L;

    private static final long 남기사_학원B = 5L;

    private static final long 차단기사_근무시간_미기재 = 7L;

    /** 시드 회차({@code CURRENT_DATE})와 겹치지 않는 날짜. */
    private static final String SERVICE_DATE = "2031-06-11";

    /** 종일 근무자의 오전 구간(07:00~10:00) 안 — 근무 시간 경고가 나오지 않아야 하는 시각이다. */
    private static final String MORNING = "08:00";

    /** 오전만 근무하는 매니저에게는 근무 시간 밖인 시각(하원 회차). */
    private static final String AFTERNOON = "16:00";

    /** 종일 근무자의 오전 구간 경계 — 시드가 07:00~10:00 으로 둔 그 양끝이다(V2 주석 참조). */
    private static final String WORK_START = "07:00";

    private static final String WORK_END = "10:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── MGR-05 배치 ───────────────────────────────────────────────────────

    /**
     * 기사와 동승자를 배치하면 {@code assignment} 행이 <b>역할별로 하나씩</b> 생긴다(목표 3).
     *
     * <p>응답의 {@code assignments[]} 와 DB 행을 둘 다 본다 — 응답만 보면 요청을 그대로 되돌려주는
     * 구현과 구별되지 않고, DB 만 보면 배치 결과를 되읽을 경로가 있는지 검사되지 않는다.
     */
    @Test
    void 기사와_동승자를_배치하면_assignment_행이_역할별로_하나씩_생긴다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), runId, 강기사_종일, 서동승_종일)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignments.length()").value(2));

        assertThat(역할별_배치(runId, "driver")).isEqualTo(강기사_종일);
        assertThat(역할별_배치(runId, "escort")).isEqualTo(서동승_종일);
    }

    /**
     * 이미 채워진 자리에 다른 매니저를 지정하면 <b>교체</b>이고, 그 자리는 여전히 한 명이다(§5.14).
     *
     * <p>{@code uk_assignment_run_role} 이 "회차당 기사 1명·동승자 1명" 을 차단으로 강제한다 — 교체를
     * 409 로 거부하면 담당자를 바꿀 경로가 사라지므로(그 자리를 비우는 엔드포인트가 부재하다) 정상
     * 조작으로 처리하되, <b>행이 늘지 않는 것</b>을 여기서 고정한다.
     */
    @Test
    void 같은_역할에_다른_매니저를_지정하면_교체되고_행은_하나로_남는다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        배치한다(관계자A_토큰(), runId, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), runId, 오기사_오전만, null).andExpect(status().isOk());

        assertThat(배치_행_수(runId, "driver")).as("행이 둘이면 한 회차에 기사가 두 명이다").isEqualTo(1);
        assertThat(역할별_배치(runId, "driver")).isEqualTo(오기사_오전만);
    }

    /** 기사만 바꾼 요청이 동승자를 지우지 않는다 — 응답이 그 회차의 현재 배치 전부를 싣는 이유다. */
    @Test
    void 한_자리만_지정한_요청은_다른_자리를_건드리지_않는다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        배치한다(관계자A_토큰(), runId, 강기사_종일, 서동승_종일).andExpect(status().isOk());

        배치한다(관계자A_토큰(), runId, 오기사_오전만, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignments.length()").value(2));

        assertThat(역할별_배치(runId, "escort")).isEqualTo(서동승_종일);
    }

    /** 기사·동승자를 둘 다 비운 요청은 {@code 422} 다 — 200 으로 받으면 "안 바꿨다" 와 "바꿨다" 가 같아진다. */
    @Test
    void 기사와_동승자를_둘_다_비우면_거부된다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), runId, null, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 역할이 어긋난 지정은 {@code 404 MANAGER_NOT_FOUND} 다(§5.14).
     *
     * <p>{@code manager.role} 이 곧 앱 권한이라(C-06), 기사를 동승자 자리에 넣으면 그 계정이 승하차를
     * 기록할 수 있는지가 보는 곳마다 갈린다.
     */
    @Test
    void 기사를_동승자_자리에_지정하면_404_이다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), runId, null, 강기사_종일)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));

        assertThat(배치_행_수(runId, "escort")).as("거부된 요청이 행을 남기면 안 된다").isZero();
    }

    /** 다른 학원의 매니저는 배치 대상이 아니다 — 그 매니저의 존재 여부를 드러내지 않는다. */
    @Test
    void 다른_학원의_매니저를_배치하면_404_이다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM manager WHERE id = ?", Long.class,
                남기사_학원B)).as("대상이 실재해야 격리가 무언가를 격리한 것이 된다").isEqualTo(ACADEMY_B_ID);

        배치한다(관계자A_토큰(), runId, 남기사_학원B, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));
    }

    /** 다른 학원의 회차를 {@code {runId}} 로 지목하면 {@code 404 RUN_NOT_FOUND} 다(Ruling 163). */
    @Test
    void 다른_학원의_회차에_배치하면_404_이다() throws Exception {
        long runId = 회차를_만든다(관계자B_토큰(), BUS_B_ID, MORNING);

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    // ── MGR-06 충돌 경고 ──────────────────────────────────────────────────

    /** 충돌이 없으면 {@code warnings[]} 는 <b>빈 배열</b>이다 — 항상 경고를 다는 구현을 막는다(목표 3). */
    @Test
    void 겹치지_않는_배치에서는_warnings_가_빈_배열이다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), runId, 강기사_종일, 서동승_종일)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * 근무 시간 밖 배치는 <b>저장되고</b> 경고가 실린다(목표 3 · Ruling 152).
     *
     * <p>{@code assignment} 행이 실제로 생긴 것을 함께 본다 — 응답 코드만 보면 차단하면서 200 을
     * 돌려주는 구현과 구별되지 않는다.
     */
    @Test
    void 근무_시간_밖에_배치해도_저장되고_warnings_에_실린다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, AFTERNOON);

        배치한다(관계자A_토큰(), runId, 오기사_오전만, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].code").value("WORK_HOURS_MISMATCH"))
                .andExpect(jsonPath("$.data.warnings[0].manager_id").value(오기사_오전만))
                .andExpect(jsonPath("$.data.warnings[0].role").value("driver"));

        assertThat(역할별_배치(runId, "driver"))
                .as("차단이 아니라 경고다 — 당일 대체·연장이 실재해 막으면 태울 수 있는 기사를 배치 불가로 만든다")
                .isEqualTo(오기사_오전만);
    }

    /**
     * 같은 매니저를 <b>같은 시각의 다른 회차</b>에 배치해도 저장되고 경고가 실린다(목표 3).
     *
     * <p>근무 시간 안({@link #MORNING})의 시각을 쓴다 — 근무 시간 밖 시각으로 재면 두 경고가 함께
     * 나와 어느 판정이 실제로 물었는지 알 수 없다.
     */
    @Test
    void 같은_매니저를_시간이_겹치는_다른_회차에_배치해도_저장되고_warnings_에_실린다() throws Exception {
        long 첫_회차 = 회차를_만든다(BUS_A1_ID, MORNING);
        long 둘째_회차 = 회차를_만든다(BUS_A2_ID, MORNING);
        배치한다(관계자A_토큰(), 첫_회차, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), 둘째_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].code").value("MANAGER_DOUBLE_BOOKED"));

        assertThat(역할별_배치(둘째_회차, "driver")).isEqualTo(강기사_종일);
        assertThat(역할별_배치(첫_회차, "driver")).as("앞 회차의 배치가 사라지면 안 된다").isEqualTo(강기사_종일);
    }

    /**
     * 근무 시간이 설정되지 않은 매니저는 {@code WORK_HOURS_NOT_SET} 이다(Ruling 165 ④).
     *
     * <p>"경고 없음" 으로 두면 <b>적합해서 조용한 것</b>과 <b>판정하지 못한 것</b>이 응답에서 같아진다.
     * 근무 시간은 등록 시 선택 항목이라 비어 있는 것이 정상 상태다.
     */
    @Test
    void 근무_시간이_설정되지_않은_매니저를_배치하면_WORK_HOURS_NOT_SET_경고가_실린다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        assertThat(jdbcTemplate.queryForObject("SELECT work_hours FROM manager WHERE id = ?", String.class,
                차단기사_근무시간_미기재)).as("시드가 이 매니저의 근무 시간을 채우면 이 테스트는 아무것도 검사하지 않는다")
                .isNull();

        배치한다(관계자A_토큰(), runId, 차단기사_근무시간_미기재, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].code").value("WORK_HOURS_NOT_SET"));

        assertThat(역할별_배치(runId, "driver")).isEqualTo(차단기사_근무시간_미기재);
    }

    /**
     * <b>두 판정이 독립임을 고정한다</b>(Ruling 165 ③) — 근무 시간이 미기재인 매니저도 중복 배치
     * 경고를 함께 받는다.
     *
     * <p>하나로 묶은 구현에서는 근무 시간 판정이 먼저 답을 내는 순간 중복 배치 경고가 사라진다.
     * 근무 시간이 없다고 해서 같은 시각에 두 대를 몰 수 있는 것은 아니다.
     */
    @Test
    void 근무_시간_미기재_매니저도_중복_배치_경고를_함께_받는다() throws Exception {
        long 첫_회차 = 회차를_만든다(BUS_A1_ID, MORNING);
        long 둘째_회차 = 회차를_만든다(BUS_A2_ID, MORNING);
        배치한다(관계자A_토큰(), 첫_회차, 차단기사_근무시간_미기재, null).andExpect(status().isOk());

        List<String> codes = JsonPath.read(본문(배치한다(관계자A_토큰(), 둘째_회차, 차단기사_근무시간_미기재, null)
                .andExpect(status().isOk()).andReturn()), "$.data.warnings[*].code");

        assertThat(codes).containsExactlyInAnyOrder("WORK_HOURS_NOT_SET", "MANAGER_DOUBLE_BOOKED");
    }

    /** 취소된 회차의 배치는 그 시각을 점유하지 않는다 — 세면 취소한 운행이 계속 경고를 만든다. */
    @Test
    void 취소된_회차의_배치는_중복_배치_경고를_만들지_않는다() throws Exception {
        long 취소할_회차 = 회차를_만든다(BUS_A1_ID, MORNING);
        long 남길_회차 = 회차를_만든다(BUS_A2_ID, MORNING);
        배치한다(관계자A_토큰(), 취소할_회차, 강기사_종일, null).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/staff/runs/" + 취소할_회차).header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk());

        배치한다(관계자A_토큰(), 남길_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /** 다른 <b>시각</b>의 회차는 중복이 아니다 — 시각 조건이 빠지면 하루 두 번 모는 기사가 전부 경고가 된다. */
    @Test
    void 같은_날_다른_시각의_회차는_중복_배치가_아니다() throws Exception {
        long 등원 = 회차를_만든다(BUS_A1_ID, MORNING);
        long 하원 = 회차를_만든다(BUS_A1_ID, "09:30");
        배치한다(관계자A_토큰(), 등원, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), 하원, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * <b>출발 시각이 다르지만 운행 구간이 겹치면</b> 중복 배치다(Phase 8 목표 13, Ruling 193).
     *
     * <p>예전 점 판정({@code r.departTime = :departTime})은 이 형태를 놓쳤다 — 출발 시각이 같을
     * 때만 잡아, 등원 07:00~08:30 운행 중인 기사에게 08:00 출발 다른 회차를 얹어도 경고가 없었다.
     * 두 회차 모두 근무 구간(07:00~10:00) 안이라 {@code WORK_HOURS_MISMATCH} 가 섞이지 않는다.
     */
    @Test
    void 출발_시각이_달라도_운행_구간이_겹치면_중복_배치_경고가_난다() throws Exception {
        long 첫_회차 = 회차를_만든다(BUS_A1_ID, WORK_START, 90);
        long 둘째_회차 = 회차를_만든다(BUS_A2_ID, MORNING);
        배치한다(관계자A_토큰(), 첫_회차, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), 둘째_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'MANAGER_DOUBLE_BOOKED')]").exists());
    }

    /**
     * 운행 구간을 구간으로 올려도 <b>실제로 떨어진</b> 두 회차는 여전히 중복이 아니다 — 구간 판정이
     * 항상 경고를 더 내는 쪽으로 치우치지 않았는지를 반대편에서 본다.
     */
    @Test
    void 운행_구간이_실제로_떨어진_두_회차는_중복_배치가_아니다() throws Exception {
        long 첫_회차 = 회차를_만든다(BUS_A1_ID, WORK_START, 60);
        long 둘째_회차 = 회차를_만든다(BUS_A2_ID, WORK_END);
        배치한다(관계자A_토큰(), 첫_회차, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), 둘째_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'MANAGER_DOUBLE_BOOKED')]").doesNotExist());
    }

    /**
     * 앞 회차 종료 시각에 <b>정확히 이어</b> 다음 회차가 시작해도 중복 배치다 — 겹침 판정은 양끝을
     * 포함한다({@link src.backend.manager.entity.WorkHours#coversWindow} 와 같은 규칙).
     *
     * <p>경계를 배타로 두면 기사가 한 회차를 마치자마자 쉴 틈 없이 다음 회차를 모는 형태가 경고 없이
     * 통과한다 — 실제로는 두 운행이 동시에 요구하는 배치라 정상 조작이 아니다.
     */
    @Test
    void 운행_구간이_경계에서_정확히_이어붙어도_중복_배치_경고가_난다() throws Exception {
        long 첫_회차 = 회차를_만든다(BUS_A1_ID, WORK_START, 60);
        long 둘째_회차 = 회차를_만든다(BUS_A2_ID, "08:00");
        배치한다(관계자A_토큰(), 첫_회차, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), 둘째_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'MANAGER_DOUBLE_BOOKED')]").exists());
    }

    /**
     * 근무 구간의 <b>양끝은 근무 시간 안</b>이다 — 07:00~10:00 근무자에게 07:00 회차도 10:00 회차도
     * 경고를 내지 않는다.
     *
     * <p>경계를 한쪽이라도 배타로 두면 <b>출근 시각 정각에 출발하는 회차가 전부 경고</b>가 된다.
     * 등원 회차는 근무 시작 시각에 맞춰 짜는 것이 정상이라 이 경계가 실제로 늘 밟힌다 — 그 상태에서는
     * 경고가 항상 켜져 있어 관계자가 읽기를 그만두고, 진짜 충돌까지 함께 묻힌다.
     *
     * <p>양끝을 <b>함께</b> 보는 이유는 한쪽만 보면 반대쪽이 검사되지 않은 채 남기 때문이다.
     */
    @Test
    void 근무_구간의_양끝_시각은_근무_시간_안이다() throws Exception {
        long 시작_정각 = 회차를_만든다(BUS_A1_ID, WORK_START);
        long 종료_정각 = 회차를_만든다(BUS_A1_ID, WORK_END);

        배치한다(관계자A_토큰(), 시작_정각, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        배치한다(관계자A_토큰(), 종료_정각, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * {@code est_duration_min} 이 채워진 회차는 <b>구간</b>으로 판정한다(Ruling 165 ② 재판정,
     * Phase 7 목표 12) — 출발 시각만 근무 구간 안이어도 운행이 그 밖까지 이어지면 경고가 난다.
     *
     * <p>강기사_종일 의 근무 구간은 {@link #WORK_START}~{@link #WORK_END}(07:00~10:00) 다. 07:00
     * 출발·4시간 운행이면 종료가 11:00 이라 구간을 벗어난다 — <b>점 판정이었다면 출발 시각(07:00)만
     * 보고 경고를 내지 않았을 자리</b>라, 이 테스트가 실패하면 구간 판정이 실제로 걸리지 않는 것이다.
     */
    @Test
    void 운행_시간이_근무_구간을_넘기면_구간_판정으로_경고가_난다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, WORK_START, 240);

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].code").value("WORK_HOURS_MISMATCH"));
    }

    /**
     * 운행 구간이 근무 구간 <b>안에 온전히 들면</b> 구간 판정에서도 경고가 없다 — 구간으로 올린 것이
     * 항상 경고를 더 내는 쪽으로 치우치지 않았는지를 반대편에서 본다.
     */
    @Test
    void 운행_시간이_근무_구간_안에_온전히_들면_경고가_없다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING, 60);

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * 운행 종료가 근무 구간의 <b>끝 경계에 정확히 닿아도</b> 경고가 없다 — 구간 판정도 점 판정과 같은
     * 양끝 포함 규칙({@link #근무_구간의_양끝_시각은_근무_시간_안이다()})을 따른다.
     */
    @Test
    void 운행_종료가_근무_구간_끝에_닿아도_경고가_없다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING, 120);

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * 같은 매니저를 <b>같은 자리에 다시</b> 지정해도 중복 배치가 아니다 — 자기 자신을 겹침으로 세지
     * 않는다.
     *
     * <p>중복 배치 판정이 지금 배치하는 회차를 제외하지 않으면, 관리 화면이 같은 값을 그대로 다시
     * 보내는 것만으로 {@code MANAGER_DOUBLE_BOOKED} 가 뜬다. 화면이 저장 버튼을 두 번 누르는 것과
     * 구별되지 않는 조작이라 실제로 늘 발생하고, 그때 나오는 경고는 <b>거짓</b>이다.
     */
    @Test
    void 같은_매니저를_같은_자리에_다시_지정해도_중복_배치_경고가_아니다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        배치한다(관계자A_토큰(), runId, 강기사_종일, null).andExpect(status().isOk());

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        assertThat(배치_행_수(runId, "driver")).isEqualTo(1);
    }

    /**
     * 요일 판정은 <b>주입된 시계의 시간대</b>를 따른다(Ruling 165 ①) — {@code ZoneId} 를 코드에 다시
     * 적은 구현을 막는다.
     *
     * <p>{@code run.depart_time} 은 {@code timestamptz} 라 같은 순간이 시간대에 따라 <b>다른 요일</b>이
     * 된다. {@link #SERVICE_DATE} 08:00 은 {@code Asia/Seoul} 기준 수요일이지만 UTC 로 보면 전날
     * 화요일 23:00 이다. 그래서 근무 요일을 수요일 하나로 좁혀 두면, 시간대를 잘못 고른 구현만
     * 요일 키를 찾지 못해 {@code WORK_HOURS_MISMATCH} 를 낸다.
     *
     * <p>근무 시간을 이 테스트 안에서 좁히는 이유는 시드 매니저가 7요일 전부를 채우고 있어
     * <b>요일을 틀려도 결과가 같기</b> 때문이다 — 시드를 고치면 다른 테스트의 전제가 함께 바뀐다.
     * 이 클래스는 {@code @Transactional} 이라 갱신이 테스트 끝에 되돌아간다.
     */
    @Test
    void 요일_판정은_주입된_시계의_시간대를_따른다() throws Exception {
        String 서울_기준_요일 = LocalDate.parse(SERVICE_DATE).getDayOfWeek().name()
                .substring(0, 3).toLowerCase(Locale.ROOT);
        jdbcTemplate.update("UPDATE manager SET work_hours = ?::jsonb WHERE id = ?",
                "{\"%s\":[{\"start\":\"%s\",\"end\":\"%s\"}]}".formatted(서울_기준_요일, WORK_START, WORK_END),
                강기사_종일);
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), runId, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    // ── 저장소의 학원 조건 (리뷰 Important 2) ─────────────────────────────

    /**
     * 배치를 되읽을 때 <b>남의 학원 매니저는 실리지 않는다</b> — {@code findAssignedManagers} 의
     * {@code m.academyId} 조건이 그것을 막는다.
     *
     * <p>이 상태는 <b>쓰기 경로로는 만들 수 없다</b> — {@code PATCH .../assignment} 가 매니저의 소속을
     * 먼저 검사하기 때문이다. 그래서 SQL 로 심는다. 도달 불가라는 이유로 단언을 두지 않으면, 그 검사를
     * 무르게 하는 변경이 들어왔을 때 <b>여기가 유일한 저지선인데 아무도 지키지 않는 상태</b>가 된다.
     *
     * <p>새는 값이 <b>매니저 이름</b>이라 격리가 무너지면 그대로 타 학원의 개인정보 노출이다.
     */
    @Test
    void 배치_조회는_타_학원_매니저를_싣지_않는다() throws Exception {
        long runId = 회차를_만든다(BUS_A1_ID, MORNING);
        타_학원_매니저를_SQL_로_배치한다(runId, 남기사_학원B);

        String body = 본문(mockMvc.perform(get("/api/v1/staff/runs?service_date=" + SERVICE_DATE)
                .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(JsonPath.<List<String>>read(body,
                "$.data[?(@.id == %d)].assignments[*].name".formatted(runId)))
                .as("타 학원 매니저가 실리면 이름이 그대로 새어 나간다")
                .isEmpty();
    }

    /**
     * 중복 배치 판정은 <b>같은 학원 안에서만</b> 본다 — {@code existsOverlappingAssignment} 의
     * {@code r.academyId} 조건이다.
     *
     * <p>타 학원 회차에 같은 매니저가 걸려 있어도 경고를 만들지 않는다. 만들면 관계자는 자기가 볼 수도
     * 고칠 수도 없는 회차 때문에 경고를 받고, 그 경고는 <b>지울 방법이 부재</b>하다.
     *
     * <p>여기서도 SQL 로 심는다 — 매니저는 한 학원에만 속하므로 쓰기 경로가 이 상태를 못 만든다.
     */
    @Test
    void 중복_배치_경고는_타_학원_회차를_세지_않는다() throws Exception {
        long 타학원_회차 = 회차를_만든다(관계자B_토큰(), BUS_B_ID, MORNING);
        타_학원_매니저를_SQL_로_배치한다(타학원_회차, 강기사_종일);
        long 우리_회차 = 회차를_만든다(BUS_A1_ID, MORNING);

        배치한다(관계자A_토큰(), 우리_회차, 강기사_종일, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'MANAGER_DOUBLE_BOOKED')]")
                        .doesNotExist());
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    /** 쓰기 경로가 막는 <b>학원을 넘는 배치</b>를 심는다 — 저장소의 학원 조건을 재려면 이 수밖에 없다. */
    private void 타_학원_매니저를_SQL_로_배치한다(long runId, long managerId) {
        jdbcTemplate.update("INSERT INTO assignment (run_id, manager_id, role, assigned_at) "
                + "VALUES (?, ?, 'driver', now())", runId, managerId);
    }

    private long 회차를_만든다(long busId, String departTime) throws Exception {
        return 회차를_만든다(관계자A_토큰(), busId, departTime);
    }

    private long 회차를_만든다(String token, long busId, String departTime) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/runs")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"service_date":"%s","direction":"to_academy","depart_time":"%s",
                         "origin_name":"배치 검증 집결지","destination_name":"바래다학원"}"""
                        .formatted(busId, SERVICE_DATE, departTime)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    /**
     * {@code est_duration_min} 을 채워 회차를 만든다 — 구간 판정(Phase 7 목표 12)을 재는 테스트 전용.
     * 값을 생략하는 위 오버로드와 달리 노선 계산이 채운 뒤의 상태를 흉내낸다.
     */
    private long 회차를_만든다(long busId, String departTime, int estDurationMin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/runs")
                .header("Authorization", 관계자A_토큰())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"service_date":"%s","direction":"to_academy","depart_time":"%s",
                         "origin_name":"배치 검증 집결지","destination_name":"바래다학원",
                         "est_duration_min":%d}"""
                        .formatted(busId, SERVICE_DATE, departTime, estDurationMin)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private ResultActions 배치한다(String token, long runId, Long driverId, Long escortId) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/runs/" + runId + "/assignment")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"driver_manager_id\":%s,\"escort_manager_id\":%s}".formatted(driverId, escortId)));
    }

    private Long 역할별_배치(long runId, String role) {
        return jdbcTemplate.queryForObject("SELECT manager_id FROM assignment WHERE run_id = ? AND role = ?",
                Long.class, runId, role);
    }

    private Integer 배치_행_수(long runId, String role) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM assignment WHERE run_id = ? AND role = ?",
                Integer.class, runId, role);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 관계자A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    private String 관계자B_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_B_ACCOUNT_ID, ACADEMY_B_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
