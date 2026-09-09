package src.backend.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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

import jakarta.persistence.EntityManager;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.entity.Assignment;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.run.entity.Run;

/**
 * §5.13 {@code /staff/managers} — MGR-01·02·03·04 · A-12 (Phase 5 목표 4).
 *
 * <p><b>이 클래스의 최우선 단언은 배치된 매니저 삭제가 {@code 409} 이고 {@code deleted_at} 이 NULL 로
 * 남는다는 것</b>이다. 응답 코드만 보면 저장 계층에서 새어 나온 {@code 500} 과 구별되지 않고,
 * {@code deleted_at} 을 안 보면 <b>삭제해 놓고 409 를 반환하는 구현</b>이 통과한다.
 *
 * <p>배치 픽스처를 {@code AssignmentRepository} 와 정적 팩토리로 만든다 — 배치 API(§5.14)는 아직
 * 부재하고, raw SQL 로 만들면 API 가 실제로 만들어 내지 않는 상태를 검사하게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffManagerControllerTest {

    /** 시드의 학원 A 관계자({@code staffA}). */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 격리 검증에서 남의 학원 매니저를 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /** 페이징 단언이 쓰는 페이지 크기 — 학원 A 의 실제 매니저 수보다 작아야 다음 페이지가 생긴다. */
    private static final int PAGE_SIZE = 2;

    /** 회차 확정 예정 시각 = 출발 30분 전(ERD {@code ck_run_confirm_at} · C-03). */
    private static final int CONFIRM_LEAD_MINUTES = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    // ── MGR-02 등록 ───────────────────────────────────────────────────────

    /** 역할은 {@code driver}·{@code escort} 두 값으로만 저장된다(§5.13 · §9.1) — 이 값이 앱 권한을 결정한다(C-06). */
    @Test
    void 매니저를_등록하면_role_이_driver_또는_escort_로만_저장된다() throws Exception {
        등록한다(관계자A_토큰(), "{\"name\":\"신기사\",\"phone\":\"010-9000-0001\",\"role\":\"driver\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("driver"));

        등록한다(관계자A_토큰(), "{\"name\":\"신동승\",\"phone\":\"010-9000-0002\",\"role\":\"escort\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("escort"));

        등록한다(관계자A_토큰(), "{\"name\":\"신정비\",\"phone\":\"010-9000-0003\",\"role\":\"mechanic\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 근무 시간은 Ruling 150 이 고정한 형태({@code 요일 → 구간 배열})로 저장된다.
     *
     * <p>구간을 <b>둘</b> 담는다 — 오전·오후로 갈리는 근무가 실재하고, 단일 구간만 검사하면 배열을
     * 첫 원소로 접어 버리는 구현이 통과한다.
     */
    @Test
    void 근무시간은_요일별_구간_배열로_저장된다() throws Exception {
        등록한다(관계자A_토큰(), """
                {"name":"이교대","phone":"010-9000-0010","role":"driver",
                 "work_hours":{"mon":[{"start":"07:00","end":"10:00"},{"start":"16:00","end":"19:00"}]}}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.work_hours.mon.length()").value(2))
                .andExpect(jsonPath("$.data.work_hours.mon[0].start").value("07:00"))
                .andExpect(jsonPath("$.data.work_hours.mon[1].end").value("19:00"));
    }

    /** 요일 키가 7종 밖이면 저장이 거부된다 — {@code jsonb} 는 스키마가 없어 DB 가 이것을 막지 못한다. */
    @Test
    void 근무시간의_요일_키가_7종_밖이면_저장이_거부된다() throws Exception {
        근무시간_등록(관계자A_토큰(), "\"funday\":[{\"start\":\"07:00\",\"end\":\"10:00\"}]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 시작이 종료보다 늦은 구간은 거부된다 — 겹침 판정(MGR-06)이 이 순서를 전제한다. */
    @Test
    void 근무시간_구간의_시작이_종료보다_늦으면_저장이_거부된다() throws Exception {
        근무시간_등록(관계자A_토큰(), "\"mon\":[{\"start\":\"18:00\",\"end\":\"09:00\"}]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 자정을 넘는 구간은 거부된다(Ruling 150) — 허용하면 겹침 판정이 두 배로 복잡해진다.
     *
     * <p>같은 {@code start < end} 조항이 역전 구간과 함께 막는다. 두 입력을 나눠 둔 이유는 조항이
     * 하나여도 <b>막아야 하는 사고가 둘</b>이기 때문이다 — 다음 사람이 자정 통과를 열려고 조건을
     * 손보면 역전 구간이 함께 열린다는 사실이 이 두 테스트로 드러난다.
     */
    @Test
    void 자정을_넘는_근무시간_구간은_저장이_거부된다() throws Exception {
        근무시간_등록(관계자A_토큰(), "\"fri\":[{\"start\":\"22:00\",\"end\":\"02:00\"}]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 구간 객체에 {@code start}·{@code end} 밖의 키가 붙으면 거부된다 — <b>Ruling 150 이 형태를 고정한
     * 이유가 이것</b>이다.
     *
     * <p>{@code jsonb} 는 스키마가 없어 무엇이든 담긴다. 여분의 키를 통과시키면 다음 사람이 그 자리에
     * 근무 조건을 하나씩 더하고, {@code MGR-06} 겹침 판정은 그것을 모르는 채 계속 두 값만 본다.
     */
    @Test
    void 근무시간_구간에_start_end_밖의_키가_있으면_저장이_거부된다() throws Exception {
        근무시간_등록(관계자A_토큰(), "\"wed\":[{\"start\":\"07:00\",\"end\":\"10:00\",\"note\":\"격주\"}]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 시각 표기가 {@code HH:mm} 이 아니면 거부된다 — 형식을 열어 두면 겹침 판정이 비교할 축을 잃는다. */
    @Test
    void 근무시간의_시각_표기가_HH_mm_이_아니면_저장이_거부된다() throws Exception {
        근무시간_등록(관계자A_토큰(), "\"tue\":[{\"start\":\"7시\",\"end\":\"10:00\"}]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── MGR-04 삭제 (목표 4) ──────────────────────────────────────────────

    /** 배치되지 않은 매니저는 삭제되고 {@code deleted_at} 이 채워진다(soft delete, ERD §7.1). */
    @Test
    void 배치되지_않은_매니저를_삭제하면_deleted_at_이_채워진다() throws Exception {
        long managerId = 등록된_매니저_id(관계자A_토큰(), "퇴사기사", "010-9100-0001");

        삭제한다(관계자A_토큰(), managerId).andExpect(status().isOk());

        assertThat(삭제_시각(managerId)).isNotNull();
    }

    /**
     * 목표 4 — 배치된 매니저 삭제는 {@code 409 MANAGER_ASSIGNED} 이고 {@code deleted_at} 이 <b>NULL 로
     * 남는다</b>.
     *
     * <p>두 단언이 각각 다른 사고를 막는다. 응답 코드만 보면 {@code 500}(선검사를 건너뛰어 저장 계층
     * 예외가 그대로 샌 것)과 구별되지 않고, {@code deleted_at} 을 안 보면 <b>지워 놓고 409 를
     * 반환하는</b> 구현이 통과한다. 후자는 soft delete 라 DB 가 되돌려 주지 않으므로 실제로 가능한
     * 형태다 — {@code manager → assignment} 의 FK RESTRICT 는 행을 지우는 DELETE 에만 걸린다.
     */
    @Test
    void 배치된_매니저를_삭제하면_409_MANAGER_ASSIGNED_이고_deleted_at_이_NULL_로_남는다() throws Exception {
        long managerId = 등록된_매니저_id(관계자A_토큰(), "배치기사", "010-9100-0002");
        배치한다(managerId, 회차를_만든다(등록된_차량_id(관계자A_토큰(), "배치용호차", "11나1111", 16)));

        삭제한다(관계자A_토큰(), managerId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MANAGER_ASSIGNED"));

        assertThat(삭제_시각(managerId)).as("차단됐는데 지워졌다면 배치된 회차의 담당자가 사라진다").isNull();
    }

    /** 삭제된 매니저는 목록에서 빠진다 — 목록 쿼리가 {@code deleted_at IS NULL} 을 잃으면 되살아난다. */
    @Test
    void 삭제된_매니저는_목록에_등장하지_않는다() throws Exception {
        long managerId = 등록된_매니저_id(관계자A_토큰(), "사라질기사", "010-9100-0003");
        assertThat(목록_본문(관계자A_토큰())).as("삭제 전에는 목록에 있어야 부재 단언이 의미를 갖는다").contains("사라질기사");

        삭제한다(관계자A_토큰(), managerId).andExpect(status().isOk());

        assertThat(목록_본문(관계자A_토큰())).doesNotContain("사라질기사");
    }

    /** 삭제된 매니저는 수정·재삭제 대상 밖이다 — 재삭제를 열어 두면 언제 그만뒀는지가 덮어써진다. */
    @Test
    void 삭제된_매니저를_다시_삭제하면_404_MANAGER_NOT_FOUND_이다() throws Exception {
        long managerId = 등록된_매니저_id(관계자A_토큰(), "두번지울기사", "010-9100-0004");
        삭제한다(관계자A_토큰(), managerId).andExpect(status().isOk());

        삭제한다(관계자A_토큰(), managerId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));
    }

    // ── MGR-01 목록 · 검색 · MGR-03 수정 ──────────────────────────────────

    /**
     * 검색어를 주면 이름이 <b>부분 일치</b>하는 매니저만 반환한다(§5.13 {@code ?q=}).
     *
     * <p>두 이름이 서로의 부분 문자열이 아니게 고른다 — 겹치면 응답 본문 대조가 검색 결과가 아니라
     * 문자열 포함을 재고, 실제로 그 형태로 한 번 헛집었다.
     */
    @Test
    void 검색어로_이름_일부를_주면_해당_매니저만_반환한다() throws Exception {
        등록된_매니저_id(관계자A_토큰(), "정검색", "010-9200-0001");
        등록된_매니저_id(관계자A_토큰(), "한제외", "010-9200-0002");

        String found = 본문(mockMvc.perform(get("/api/v1/staff/managers?q=검색&size=100")
                .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(found).as("이름 일부만 줘도 찾아야 한다").contains("정검색");
        assertThat(found).doesNotContain("한제외");
    }

    /**
     * 검색어의 LIKE 와일드카드는 리터럴로 다뤄진다 — {@code q="%"} 하나가 전체 매칭이 되면 검색이
     * 필터가 아니라 전량 조회가 된다.
     */
    @Test
    void 검색어의_와일드카드는_리터럴로_다뤄진다() throws Exception {
        등록된_매니저_id(관계자A_토큰(), "와일드기사", "010-9200-0003");

        String found = 본문(mockMvc.perform(get("/api/v1/staff/managers?q=%25&size=100")
                .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(found).doesNotContain("와일드기사");
    }

    /** 역할 수정은 그대로 반영된다 — 이 값이 매니저 앱의 화면 구성과 권한을 결정한다(C-06 · §5.13). */
    @Test
    void 매니저의_역할을_수정하면_반영된다() throws Exception {
        long managerId = 등록된_매니저_id(관계자A_토큰(), "전환기사", "010-9300-0001");

        수정한다(관계자A_토큰(), managerId, "{\"role\":\"escort\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("escort"))
                .andExpect(jsonPath("$.data.name").value("전환기사"));
    }

    /**
     * 요청한 {@code size} 가 <b>실제로 반환 건수를 제한</b>하고, 다음 페이지가 나머지를 담는다(§1.8).
     *
     * <p>차량 목록과 같은 축이다 — 같은 공백이 이 저장소의 목록 API 전반에서 발견됐다(게이트 리뷰
     * A1). 매니저 쪽에 따로 거는 이유는 두 서비스가 각자 {@code Pageable} 을 만들어, 한쪽을 고쳐도
     * 다른 쪽은 그대로 남기 때문이다.
     */
    @Test
    void 매니저_목록은_요청한_size_보다_많은_행을_반환하지_않고_다음_페이지가_나머지를_담는다() throws Exception {
        등록된_매니저_id(관계자A_토큰(), "페이징기사1", "010-9600-0001");
        등록된_매니저_id(관계자A_토큰(), "페이징기사2", "010-9600-0002");

        String 첫_페이지 = 페이지_본문(관계자A_토큰(), 0, PAGE_SIZE);
        String 둘째_페이지 = 페이지_본문(관계자A_토큰(), 1, PAGE_SIZE);

        assertThat(JsonPath.<List<Object>>read(첫_페이지, "$.data.items"))
                .as("요청 size 를 무시하고 전건을 반환하면 안 된다").hasSize(PAGE_SIZE);
        assertThat(JsonPath.<Integer>read(첫_페이지, "$.data.total_count"))
                .as("total_count 는 한 페이지 건수가 아니라 전체 행 수다").isGreaterThan(PAGE_SIZE);
        assertThat(JsonPath.<Boolean>read(첫_페이지, "$.data.has_next")).isTrue();
        assertThat(JsonPath.<List<Integer>>read(둘째_페이지, "$.data.items[*].id"))
                .as("다음 페이지가 앞 페이지와 겹치면 뒤쪽 매니저에 닿을 수단이 부재하다")
                .doesNotContainAnyElementsOf(JsonPath.read(첫_페이지, "$.data.items[*].id"));
    }

    /**
     * {@code total_count} 가 <b>실제 행 수</b>를 따른다 — 한 건을 등록하면 정확히 1 늘어난다.
     *
     * <p>보고서 §2.8 에서 스스로 신고한 공백이 이 자리다. 총수를 절대값으로 고정하기 어렵다는 판단은
     * 맞았으나, <b>증분</b>으로 재면 시드와 무관하게 고정된다. {@code size=1} 로 불러 페이지 건수와
     * 총수를 갈라 두는 것이 {@code items.size()} 위조를 드러내는 축이다.
     */
    @Test
    void 매니저_total_count_는_등록한_행_수만큼_증가한다() throws Exception {
        int before = 총수(관계자A_토큰());

        등록된_매니저_id(관계자A_토큰(), "총수확인기사", "010-9700-0001");

        assertThat(총수(관계자A_토큰()))
                .as("total_count 가 페이지에 담긴 건수를 되돌려주면 등록해도 값이 움직이지 않는다")
                .isEqualTo(before + 1);
    }

    // ── 학원 격리 (규칙 7) ────────────────────────────────────────────────

    /** 다른 학원의 매니저를 {@code {id}} 로 지목하면 {@code 404 MANAGER_NOT_FOUND} 다 — 존재 여부를 흘리지 않는다. */
    @Test
    void 다른_학원의_매니저를_수정하면_404_MANAGER_NOT_FOUND_이다() throws Exception {
        long managerOfAcademyA = 등록된_매니저_id(관계자A_토큰(), "A전용기사", "010-9400-0001");

        수정한다(관계자B_토큰(), managerOfAcademyA, "{\"name\":\"가로챈이름\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));
    }

    /** 다른 학원의 매니저는 삭제도 되지 않는다 — 수정만 막고 삭제를 열어 두면 격리가 그쪽으로 샌다. */
    @Test
    void 다른_학원의_매니저를_삭제하면_404_MANAGER_NOT_FOUND_이다() throws Exception {
        long managerOfAcademyA = 등록된_매니저_id(관계자A_토큰(), "A전용기사2", "010-9400-0002");

        삭제한다(관계자B_토큰(), managerOfAcademyA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));

        assertThat(삭제_시각(managerOfAcademyA)).isNull();
    }

    /**
     * 목록은 소속 학원의 매니저만 싣는다 — 조건이 빠져도 목록은 동작하므로 여기서만 드러난다.
     *
     * <p>B 의 목록이 비어 있지 않은 것을 함께 본다 — 빼면 목록이 통째로 비어도 앞 단언이 통과한다.
     */
    @Test
    void 매니저_목록은_소속_학원의_매니저만_싣는다() throws Exception {
        등록된_매니저_id(관계자A_토큰(), "A소속기사", "010-9500-0001");

        String bodyOfB = 목록_본문(관계자B_토큰());

        assertThat(bodyOfB).doesNotContain("A소속기사");
        assertThat(bodyOfB).as("B 의 목록이 비면 위 부재 단언은 아무것도 검사하지 않는다").contains("\"name\"");
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    /**
     * 배치 대상 회차를 만든다 — {@code assignment.run_id} 가 FK NN 이라 회차가 없으면 배치 행이 없다.
     *
     * <p>{@code RunRepository} 를 만들지 않고 {@link EntityManager} 로 저장한다 — 회차를 만드는
     * 프로덕션 경로(SCH-02·03)가 이 태스크 범위 밖이라, 저장소를 두면 호출자 없는 프로덕션 코드가 된다.
     * 정적 팩토리를 거치므로 상태 자체는 그 경로가 만들 것과 같다.
     */
    private long 회차를_만든다(long busId) {
        OffsetDateTime departAt = OffsetDateTime.now(clock).plusHours(3);
        Run run = Run.forSchedule(ACADEMY_A_ID, busId, null, LocalDate.now(clock), Direction.TO_ACADEMY,
                departAt, departAt.minusMinutes(CONFIRM_LEAD_MINUTES), "중앙 집결지", "바래다학원 A", null);
        entityManager.persist(run);
        entityManager.flush();
        return run.getId();
    }

    /** 매니저를 회차에 배치한다 — 배치 API(§5.14)는 T5 소유라 저장소와 정적 팩토리로 만든다. */
    private void 배치한다(long managerId, long runId) {
        assignmentRepository.save(Assignment.uponAssignment(runId, managerId, ManagerRole.DRIVER,
                OffsetDateTime.now(clock), STAFF_A_ACCOUNT_ID));
        entityManager.flush();
    }

    /** {@code manager.deleted_at} 을 DB 에서 직접 읽는다 — 응답이 아니라 저장된 상태가 판정 대상이다. */
    private OffsetDateTime 삭제_시각(long managerId) {
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM manager WHERE id = ?",
                OffsetDateTime.class, managerId);
    }

    private ResultActions 등록한다(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/managers")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 근무시간_등록(String token, String workHoursEntry) throws Exception {
        return 등록한다(token, "{\"name\":\"근무시간검증\",\"phone\":\"010-9900-0001\",\"role\":\"driver\","
                + "\"work_hours\":{" + workHoursEntry + "}}");
    }

    private ResultActions 수정한다(String token, long managerId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/managers/" + managerId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 삭제한다(String token, long managerId) throws Exception {
        return mockMvc.perform(delete("/api/v1/staff/managers/" + managerId).header("Authorization", token));
    }

    private long 등록된_매니저_id(String token, String name, String phone) throws Exception {
        MvcResult result = 등록한다(token,
                "{\"name\":\"%s\",\"phone\":\"%s\",\"role\":\"driver\"}".formatted(name, phone))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private long 등록된_차량_id(String token, String busNo, String plateNo, int capacity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/buses")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bus_no\":\"%s\",\"plate_no\":\"%s\",\"capacity\":%d}"
                        .formatted(busNo, plateNo, capacity)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private String 페이지_본문(String token, int page, int size) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/managers?page=%d&size=%d".formatted(page, size))
                .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** {@code size=1} 로 물어 페이지 건수와 총수가 갈린 상태에서 총수를 읽는다. */
    private int 총수(String token) throws Exception {
        return JsonPath.read(페이지_본문(token, 0, 1), "$.data.total_count");
    }

    private String 목록_본문(String token) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/managers?size=100").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
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
