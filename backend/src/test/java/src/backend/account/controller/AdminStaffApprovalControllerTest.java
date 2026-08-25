package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §6.4·§6.5 {@code /admin/staff-signup-requests} — 메인 관리자가 처리하는 관계자 가입 승인
 * (ACAD-05, O-02).
 *
 * <p>관계자 축(§5.1·§5.2)과 <b>갈리는 것</b>이 이 클래스의 검사 대상이다 — 대상 역할이
 * {@code staff} 뿐이고, 정원 판정이 붙고, 레코드 연결 대신 {@code academy_staff} 행 생성이 그
 * 역할을 하며, 학원 격리의 예외 구역이라 전 학원 요청을 본다.
 *
 * <p>시드가 정원 초과 재료를 이미 갖고 있다 — 학원 A(id 1)에 재직 관계자 {@code staffA} 가 있는
 * 상태에서 관계자 대기 요청(id 1)이 함께 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStaffApprovalControllerTest {

    /** 시드 {@code signup_request} 1번 — 학원 A 의 관계자 가입 요청. 학원 A 에는 이미 재직 관계자가 있다. */
    private static final long STAFF_REQUEST_ON_FULL_ACADEMY = 1L;

    private static final long STAFF_PENDING_ACCOUNT = 4L;

    /** 시드 {@code signup_request} 2번 — 학부모 요청이라 이 축의 대상 밖이다. */
    private static final long PARENT_REQUEST = 2L;

    private static final long SYSTEM_ADMIN_ACCOUNT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * JPA 가 쌓아 둔 변경을 DB 로 내보낸 뒤 raw SQL 로 읽는다.
     *
     * <p>테스트가 트랜잭션을 열고 있어 서비스의 {@code @Transactional} 이 그것에 합류한다 — 메서드가
     * 끝나도 커밋·flush 가 일어나지 않아, 이 호출이 없으면 {@link JdbcTemplate} 이 <b>변경 전</b> 값을
     * 읽는다. 프로덕션에서는 요청마다 트랜잭션이 끝나므로 이 사정이 부재하다.
     */
    private void 반영한다() {
        entityManager.flush();
    }

    // ── §6.4 목록 ──────────────────────────────────────────────────────────

    /**
     * 이 목록은 {@code role=staff} 요청만 싣는다(§6.4).
     *
     * <p>학부모 요청이 섞이면 메인 관리자가 학원 관계자의 승인 대상을 대신 처리할 수 있게 되고,
     * 그것은 "관계자가 자기 학원 사람을 승인한다" 는 승인 주체 규정 자체를 무너뜨린다.
     */
    @Test
    void 메인_관리자_목록은_role_staff_요청만_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/staff-signup-requests").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.request_id == %d)]"
                        .formatted(STAFF_REQUEST_ON_FULL_ACADEMY)).exists())
                .andExpect(jsonPath("$.data.items[?(@.request_id == %d)]".formatted(PARENT_REQUEST))
                        .doesNotExist());
    }

    /**
     * 항목은 학원 정보와 현재 관계자 수를 함께 싣는다(§6.4).
     *
     * <p>{@code academy_staff_count} 가 없으면 메인 관리자는 <b>승인을 눌러 409 를 받아야</b> 그
     * 학원에 이미 관계자가 있다는 것을 알게 된다 — 정원이 찬 요청을 화면에서 미리 가려낼 수단이 부재하다.
     */
    @Test
    void 목록_항목은_학원_정보와_현재_관계자_수를_함께_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/staff-signup-requests").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].request_id").value((int) STAFF_REQUEST_ON_FULL_ACADEMY))
                .andExpect(jsonPath("$.data.items[0].name").value("박대기"))
                .andExpect(jsonPath("$.data.items[0].phone").value("010-0000-0004"))
                .andExpect(jsonPath("$.data.items[0].academy.id").value(1))
                .andExpect(jsonPath("$.data.items[0].academy.name").value("바래다학원 A"))
                .andExpect(jsonPath("$.data.items[0].academy.region").value("서울"))
                .andExpect(jsonPath("$.data.items[0].academy.code").value("BARAEDA-A"))
                .andExpect(jsonPath("$.data.items[0].academy_staff_count").value(1))
                .andExpect(jsonPath("$.data.items[0].requested_at").exists())
                .andExpect(jsonPath("$.data.total_count").value(1));
    }

    /**
     * 전 학원 범위다(§1.5 예외) — 다른 학원의 관계자 요청도 같은 목록에 실린다.
     *
     * <p>메인 관리자가 학원별로 나뉘어 보인다면 격리 예외 구역이 열리지 않은 것이고, 그러면 학원마다
     * 승인 주체를 따로 두어야 해 §6 콘솔 자체가 성립하지 않는다.
     */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T2ADM1', 'P3T2타학원', '부산', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T2ADM1'), 'p3t2staffx', 'x', 'P3T2타학원관계자', "
                    + "'010-0000-3001', 'staff', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2staffx'), "
                    + "(SELECT id FROM academy WHERE code = 'P3T2ADM1'), 'staff', 'system_admin', 'pending', now())"
    })
    void 메인_관리자_목록은_전_학원_요청을_함께_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/staff-signup-requests").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(2))
                .andExpect(jsonPath("$.data.items[?(@.academy.code == 'P3T2ADM1')].academy_staff_count").value(0));
    }

    // ── §6.5 정원 ──────────────────────────────────────────────────────────

    /**
     * 이미 재직 관계자가 있는 학원의 추가 승인은 {@code 409 STAFF_QUOTA_EXCEEDED} 다(§6.5).
     *
     * <p>{@code academy_staff} 행 수를 함께 확인하는 이유는, 409 를 돌려주면서 행은 만들어 버린
     * 구현이 응답 단언만으로는 잡히지 않기 때문이다 — 그 상태에서는 다음 조회부터 관계자가 둘이다.
     */
    @Test
    void 이미_active_관계자가_있는_학원의_관계자_가입_요청을_승인하면_409_STAFF_QUOTA_EXCEEDED_다() throws Exception {
        처리한다(STAFF_REQUEST_ON_FULL_ACADEMY, "{\"accept\": true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STAFF_QUOTA_EXCEEDED"));

        assertThat(재직_관계자_수(1L)).isEqualTo(1);
        assertThat(계정_상태(STAFF_PENDING_ACCOUNT)).isEqualTo("pending");
    }

    /** 관계자가 없는 학원의 요청은 수락되어 {@code academy_staff} 행이 생긴다(§6.5) — 이것이 이 축의 "연결" 이다. */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T2ADM2', 'P3T2빈학원', '대전', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T2ADM2'), 'p3t2staffy', 'x', 'P3T2빈학원관계자', "
                    + "'010-0000-3002', 'staff', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2staffy'), "
                    + "(SELECT id FROM academy WHERE code = 'P3T2ADM2'), 'staff', 'system_admin', 'pending', now())"
    })
    void 관계자가_없는_학원의_요청을_수락하면_academy_staff_가_생기고_계정이_active_가_된다() throws Exception {
        처리한다(요청_식별자("p3t2staffy"), "{\"accept\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"))
                .andExpect(jsonPath("$.data.decided_at").exists());

        long academyId = 학원_식별자("P3T2ADM2");
        assertThat(재직_관계자_수(academyId)).isEqualTo(1);
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT account_id FROM academy_staff WHERE academy_id = ? AND status = 'active'",
                Long.class, academyId)).isEqualTo(계정_식별자("p3t2staffy"));
        assertThat(계정_상태(계정_식별자("p3t2staffy"))).isEqualTo("active");
    }

    /** 거절은 계정을 {@code rejected} 로 만들고 {@code academy_staff} 를 만들지 않는다(§6.5). */
    @Test
    void 관계자_요청을_거절하면_계정이_rejected_가_되고_academy_staff_는_생기지_않는다() throws Exception {
        처리한다(STAFF_REQUEST_ON_FULL_ACADEMY, "{\"accept\": false, \"reject_reason\": \"운영 계약 미체결\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("rejected"));

        assertThat(계정_상태(STAFF_PENDING_ACCOUNT)).isEqualTo("rejected");
        assertThat(재직_관계자_수(1L))
                .as("거절이 관계자 행을 만들면 정원 판정이 거절 경로로 우회된다")
                .isEqualTo(1);
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by FROM signup_request WHERE id = ?", Long.class, STAFF_REQUEST_ON_FULL_ACADEMY))
                .isEqualTo(SYSTEM_ADMIN_ACCOUNT);
    }

    /** 이미 처리된 요청의 재처리는 {@code 409 APPROVAL_ALREADY_DECIDED} 다(§6.5). */
    @Test
    void 이미_처리된_관계자_요청을_다시_처리하면_409_APPROVAL_ALREADY_DECIDED_다() throws Exception {
        처리한다(STAFF_REQUEST_ON_FULL_ACADEMY, "{\"accept\": false, \"reject_reason\": \"첫 처리\"}")
                .andExpect(status().isOk());

        처리한다(STAFF_REQUEST_ON_FULL_ACADEMY, "{\"accept\": false, \"reject_reason\": \"두 번째\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_DECIDED"));
    }

    /** {@code accept=false} 인데 사유가 없으면 {@code 422 VALIDATION_FAILED} 다(§6.5). */
    @Test
    void 관계자_요청_거절에_reject_reason_이_없으면_422_VALIDATION_FAILED_다() throws Exception {
        처리한다(STAFF_REQUEST_ON_FULL_ACADEMY, "{\"accept\": false}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(요청_상태(STAFF_REQUEST_ON_FULL_ACADEMY)).isEqualTo("pending");
    }

    /**
     * 관계자가 아닌 요청은 이 축의 대상 밖이다(§6.5 는 {@code role=staff} 만 다룬다).
     *
     * <p>이 단언이 없으면 메인 관리자가 학부모 요청을 <b>레코드 연결 없이</b> 승인할 수 있다 —
     * AUTH-11 이 요구하는 연결이 통째로 빠진 활성 계정이 만들어지고, 그 계정은 로그인만 된다.
     */
    @Test
    void 메인_관리자가_학부모_요청을_처리하려_하면_404_SIGNUP_REQUEST_NOT_FOUND_다() throws Exception {
        처리한다(PARENT_REQUEST, "{\"accept\": true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUEST_NOT_FOUND"));

        assertThat(계정_상태(8L)).isEqualTo("pending");
    }

    /** 미존재 요청 지정은 {@code 404 SIGNUP_REQUEST_NOT_FOUND} 다(§6.5). */
    @Test
    void 없는_관계자_요청을_처리하면_404_SIGNUP_REQUEST_NOT_FOUND_다() throws Exception {
        처리한다(99999999L, "{\"accept\": true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUEST_NOT_FOUND"));
    }

    /** {@code STAFF_APPROVE} 는 메인 관리자만 보유한다 — 관계자가 자기 후임을 승인할 수 없다. */
    @Test
    void 메인_관리자가_아닌_계정의_관계자_요청_목록_조회는_403_이다() throws Exception {
        String staffToken = "Bearer " + tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/admin/staff-signup-requests").header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    private ResultActions 처리한다(long requestId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + requestId + "/decide")
                .header("Authorization", 메인관리자_토큰())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int 재직_관계자_수(long academyId) {
        반영한다();
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM academy_staff WHERE academy_id = ? AND status = 'active'",
                Integer.class, academyId);
    }

    private String 계정_상태(long accountId) {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, accountId);
    }

    private String 요청_상태(long requestId) {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM signup_request WHERE id = ?", String.class, requestId);
    }

    private long 요청_식별자(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT r.id FROM signup_request r JOIN account a ON a.id = r.account_id WHERE a.login_id = ?",
                Long.class, loginId);
    }

    private long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    private long 학원_식별자(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
    }
}
