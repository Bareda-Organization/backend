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
import src.backend.global.request.PageParams;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.1·§5.2 {@code /staff/signup-requests} — 관계자가 처리하는 가입 승인(AUTH-10·11, A-02).
 *
 * <p>시드가 이 축의 재료를 이미 갖고 있다 — 학원 A(id 1)에 <b>관계자가 승인할</b> 학부모 대기 요청
 * (id 2)과 <b>메인 관리자가 승인할</b> 관계자 대기 요청(id 1)이 함께 있다. 두 요청이 같은 학원에
 * 있다는 것이 이 클래스의 핵심 재료다 — 축을 가르지 않은 구현은 관계자에게 둘 다 보여 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignupApprovalControllerTest {

    /** 시드 학원 A. 관계자 계정 {@code staffA}(id 2)가 재직 중이다. */
    private static final long ACADEMY_A = 1L;

    /** 시드 학원 B — 학원 격리 단언의 반대편이다. */
    private static final long ACADEMY_B = 2L;

    private static final long STAFF_A_ACCOUNT = 2L;

    /** 시드 {@code signup_request} 2번 — {@code parentPending}(계정 8)의 학부모 가입 요청. */
    private static final long PARENT_REQUEST = 2L;

    /** 시드 {@code signup_request} 1번 — {@code staffPending}(계정 4)의 <b>관계자</b> 가입 요청. */
    private static final long STAFF_REQUEST = 1L;

    private static final long PARENT_PENDING_ACCOUNT = 8L;

    /** 시드 학생 — 학원 A 소속이며 계정 미연결이다. */
    private static final long STUDENT_A1 = 1L;
    private static final long STUDENT_A2 = 2L;

    /** 시드 학생 — 학원 <b>B</b> 소속이라 학원 A 관계자의 연결 대상이 아니다. */
    private static final long STUDENT_B1 = 6L;

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

    // ── §5.1 목록 ──────────────────────────────────────────────────────────

    /**
     * {@code role=staff} 요청은 이 목록의 대상 밖이다(§5.1).
     *
     * <p>목록에서 빼지 않으면 관계자가 자기 후임 승인 요청을 보게 되고, {@code decide} 만 403 이라
     * 화면에 <b>처리할 수 없는 항목</b>이 영영 쌓인다. 학부모 요청이 함께 보이는 것까지 단언하는
     * 이유는, 목록을 통째로 비운 구현도 앞 단언만으로는 통과하기 때문이다.
     */
    @Test
    void 관계자의_가입_요청_목록에_role_staff_요청이_등장하지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/signup-requests").header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.request_id == %d)]".formatted(PARENT_REQUEST)).exists())
                .andExpect(jsonPath("$.data.items[?(@.request_id == %d)]".formatted(STAFF_REQUEST)).doesNotExist())
                .andExpect(jsonPath("$.data.items[?(@.role == 'staff')]").doesNotExist());
    }

    /** 목록은 §1.8 봉투에 미처리 배지({@code pending_count})를 더한 형태다(§5.1). */
    @Test
    void 목록_응답은_페이징_봉투에_pending_count_를_더해_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/signup-requests").header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(PageParams.DEFAULT_SIZE))
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false))
                .andExpect(jsonPath("$.data.pending_count").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("조대기"))
                .andExpect(jsonPath("$.data.items[0].role").value("parent"))
                .andExpect(jsonPath("$.data.items[0].phone").value("010-1000-0004"))
                .andExpect(jsonPath("$.data.items[0].requested_at").exists());
    }

    /**
     * 목록은 소속 학원으로 격리된다(§1.5) — 학원 B 관계자에게 학원 A 의 요청이 보이면 안 된다.
     *
     * <p>{@code total_count} 를 함께 보는 이유는 항목 배열만 보면 <b>페이지에 안 실렸을 뿐</b>인
     * 경우와 구별되지 않기 때문이다.
     */
    @Test
    void 목록은_소속_학원_요청만_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/signup-requests").header("Authorization", 관계자_토큰(ACADEMY_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ── §5.2 축 분리 ───────────────────────────────────────────────────────

    /** {@code role=staff} 요청의 승인 주체는 메인 관리자다(§5.2) — 관계자 경로에서는 {@code 403 FORBIDDEN}. */
    @Test
    void 관계자가_role_staff_요청을_처리하려_하면_403_FORBIDDEN_이다() throws Exception {
        처리한다(STAFF_REQUEST, ACADEMY_A, "{\"accept\": true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(계정_상태(4L))
                .as("403 을 돌려주면서 계정만 활성화하면 응답과 부수효과가 갈린다")
                .isEqualTo("pending");
    }

    /** 타 학원 요청은 소속 밖 자원이다(§1.5) — {@code 403 ACADEMY_SCOPE_VIOLATION}. */
    @Test
    void 관계자는_타_학원_가입_요청을_처리할_수_없다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_B, "{\"accept\": true, \"link\": {\"student_ids\": [%d]}}"
                .formatted(STUDENT_A1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));

        assertThat(계정_상태(PARENT_PENDING_ACCOUNT)).isEqualTo("pending");
    }

    /** 미존재 요청 지정은 {@code 404 SIGNUP_REQUEST_NOT_FOUND} 다(§5.2). */
    @Test
    void 없는_가입_요청을_처리하면_404_SIGNUP_REQUEST_NOT_FOUND_다() throws Exception {
        처리한다(99999999L, ACADEMY_A, "{\"accept\": true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUEST_NOT_FOUND"));
    }

    /**
     * 이미 처리된 요청의 재처리는 {@code 409 APPROVAL_ALREADY_DECIDED} 다(§5.2).
     *
     * <p>재처리가 통과하면 거절했던 계정을 두 번째 호출이 조용히 활성화한다 — 응답 코드만 200 이라
     * 어떤 화면도 그 사실을 드러내지 않는다.
     */
    @Test
    void 이미_처리된_요청을_다시_처리하면_409_APPROVAL_ALREADY_DECIDED_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 거절_본문("서류 미비"))
                .andExpect(status().isOk());

        처리한다(PARENT_REQUEST, ACADEMY_A, 거절_본문("두 번째 시도"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_DECIDED"));
    }

    /**
     * 이미 처리된 건은 <b>연결 누락보다 먼저</b> 걸린다 — {@code 409} 이지 {@code 422 LINK_REQUIRED} 가 아니다.
     *
     * <p>순서가 뒤집히면 관계자 화면이 "자녀를 지정하세요" 를 띄운다 — 지정해도 두 번째 호출에서
     * 다시 막히므로, 사용자는 무엇이 문제인지 모른 채 같은 화면을 반복하게 된다. 위
     * {@code 이미_처리된_요청을_다시_처리하면_409} 는 거절→거절이라 이 순서를 검사하지 못한다.
     */
    @Test
    void 이미_처리된_요청을_link_없이_수락하려_하면_LINK_REQUIRED_가_아니라_409_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 거절_본문("먼저 거절"))
                .andExpect(status().isOk());

        처리한다(PARENT_REQUEST, ACADEMY_A, "{\"accept\": true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_DECIDED"));
    }

    /** {@code accept=false} 인데 사유가 없으면 {@code 422 VALIDATION_FAILED} 다(§5.2). */
    @Test
    void accept_false_인데_reject_reason_이_없으면_422_VALIDATION_FAILED_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, "{\"accept\": false}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(요청_상태(PARENT_REQUEST))
                .as("거부한 요청은 대기 상태로 남아야 관계자가 사유를 적어 다시 처리할 수 있다")
                .isEqualTo("pending");
    }

    /** 거절은 계정을 {@code rejected} 로 만들고 처리자·일시·사유를 요청 행에 적재한다(§5.2). */
    @Test
    void 관계자가_학부모_가입_요청을_거절하면_계정이_rejected_가_된다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 거절_본문("재학 확인 불가"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("rejected"))
                .andExpect(jsonPath("$.data.decided_at").exists());

        assertThat(계정_상태(PARENT_PENDING_ACCOUNT)).isEqualTo("rejected");
        assertThat(요청_상태(PARENT_REQUEST)).isEqualTo("rejected");
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM signup_request WHERE id = ?", String.class, PARENT_REQUEST))
                .isEqualTo("재학 확인 불가");
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by FROM signup_request WHERE id = ?", Long.class, PARENT_REQUEST))
                .as("누가 처리했는지 없으면 승인 이력이 판정 수단을 잃는다")
                .isEqualTo(STAFF_A_ACCOUNT);
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_at IS NOT NULL FROM signup_request WHERE id = ?", Boolean.class, PARENT_REQUEST))
                .isTrue();
    }

    // ── AUTH-11 계정 ↔ 레코드 연결 ─────────────────────────────────────────

    /**
     * 수락에는 레코드 연결이 필수다(AUTH-11 · §5.2) — 누락은 {@code 422 LINK_REQUIRED}.
     *
     * <p>연결 없이 {@code active} 로 만들면 "로그인은 되는데 아무 데이터도 못 보는 계정" 이 생긴다.
     * 계정 상태를 함께 확인하는 이유는, 422 를 돌려주면서 활성화까지 해 버린 구현이 응답 단언만으로는
     * 잡히지 않기 때문이다.
     */
    @Test
    void 학부모_수락_시_link_가_없으면_422_LINK_REQUIRED_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, "{\"accept\": true}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LINK_REQUIRED"));

        assertThat(계정_상태(PARENT_PENDING_ACCOUNT)).isEqualTo("pending");
        assertThat(요청_상태(PARENT_REQUEST)).isEqualTo("pending");
    }

    /** 빈 배열은 "연결 대상을 지정하지 않은 것" 과 같다 — 목록만 있으면 통과하는 구멍을 막는다. */
    @Test
    void 학부모_수락_시_student_ids_가_빈_배열이면_422_LINK_REQUIRED_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, "{\"accept\": true, \"link\": {\"student_ids\": []}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LINK_REQUIRED"));
    }

    /** 수락은 계정을 활성화하고 {@code guardian} · {@code guardian_student} 를 적재한다(AUTH-11). */
    @Test
    void 학부모_수락_시_연결한_자녀가_guardian_student_에_적재된다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 수락_본문(STUDENT_A1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        assertThat(계정_상태(PARENT_PENDING_ACCOUNT)).isEqualTo("active");
        assertThat(연결된_자녀_수(PARENT_PENDING_ACCOUNT))
                .as("연결 없이 활성화하면 로그인은 되는데 아무 자녀도 못 보는 계정이 된다")
                .isEqualTo(1);
        반영한다();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
                WHERE g.account_id = ? AND gs.student_id = ?
                """, Integer.class, PARENT_PENDING_ACCOUNT, STUDENT_A1)).isEqualTo(1);
    }

    /** 다자녀는 <b>연결 추가만</b> 한다(§5.2) — 한 번의 수락으로 두 행이 생겨야 재가입이 필요 없다. */
    @Test
    void 자녀가_둘인_학부모는_한_번의_수락으로_guardian_student_가_2행_생긴다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 수락_본문(STUDENT_A1, STUDENT_A2))
                .andExpect(status().isOk());

        assertThat(연결된_자녀_수(PARENT_PENDING_ACCOUNT)).isEqualTo(2);
        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian WHERE account_id = ?", Integer.class, PARENT_PENDING_ACCOUNT))
                .as("자녀 수만큼 보호자 행을 만들면 학부모 한 명이 여러 사람이 된다")
                .isEqualTo(1);
    }

    /**
     * 타 학원 학생 식별자로는 연결할 수 없다 — {@code 404 STUDENT_NOT_FOUND}(§5.2).
     *
     * <p>이 단언이 없으면 학원 격리가 <b>승인 경로로</b> 우회된다. 403 이 아니라 404 인 것은 §5.2 가
     * 그렇게 정했기 때문이며, 존재 여부를 노출하지 않는 쪽이다.
     */
    @Test
    void 타_학원_학생_id_로_연결을_시도하면_404_STUDENT_NOT_FOUND_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 수락_본문(STUDENT_B1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        assertThat(계정_상태(PARENT_PENDING_ACCOUNT)).isEqualTo("pending");
        assertThat(연결된_자녀_수(PARENT_PENDING_ACCOUNT))
                .as("한 명이라도 실패하면 연결 전체가 되돌려져야 부분 연결 계정이 남지 않는다")
                .isZero();
    }

    /** 자녀 목록 중 하나만 타 학원이어도 전체가 되돌려진다 — 부분 연결 계정은 관측할 수단이 부재하다. */
    @Test
    void 자녀_목록에_타_학원_학생이_섞이면_이미_처리한_연결까지_되돌려진다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 수락_본문(STUDENT_A1, STUDENT_B1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        assertThat(연결된_자녀_수(PARENT_PENDING_ACCOUNT)).isZero();
    }

    /** 같은 자녀를 두 번 연결하려 하면 {@code 409 ALREADY_LINKED} 다(§8.5) — DB UNIQUE 를 500 으로 흘리지 않는다. */
    @Test
    void 같은_자녀를_두_번_연결하면_409_ALREADY_LINKED_다() throws Exception {
        처리한다(PARENT_REQUEST, ACADEMY_A, 수락_본문(STUDENT_A1, STUDENT_A1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_LINKED"));
    }

    /** 기사·동승자는 {@code link.manager_id} 가 필수다(§5.2) — 누락은 {@code 422 LINK_REQUIRED}. */
    @Test
    @Sql(statements = {
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "(1, 'p3t2driver', 'x', 'P3T2대기기사', '010-0000-2001', 'driver', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2driver'), 1, 'driver', 'staff', "
                    + "'pending', now())",
            "INSERT INTO manager (academy_id, account_id, name, phone, role) VALUES "
                    + "(1, NULL, 'P3T2미연결기사', '010-0000-2002', 'driver')"
    })
    void 기사_수락_시_manager_id_가_없으면_422_LINK_REQUIRED_다() throws Exception {
        처리한다(요청_식별자("p3t2driver"), ACADEMY_A, "{\"accept\": true}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LINK_REQUIRED"));
    }

    /** 기사 수락은 {@code manager.account_id} 를 채운다 — 이게 없으면 배치된 회차를 찾을 근거가 부재하다. */
    @Test
    @Sql(statements = {
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "(1, 'p3t2driver', 'x', 'P3T2대기기사', '010-0000-2001', 'driver', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2driver'), 1, 'driver', 'staff', "
                    + "'pending', now())",
            "INSERT INTO manager (academy_id, account_id, name, phone, role) VALUES "
                    + "(1, NULL, 'P3T2미연결기사', '010-0000-2002', 'driver')"
    })
    void 기사_수락_시_manager_레코드에_계정이_연결된다() throws Exception {
        long managerId = 매니저_식별자("P3T2미연결기사");

        처리한다(요청_식별자("p3t2driver"), ACADEMY_A, "{\"accept\": true, \"link\": {\"manager_id\": %d}}"
                .formatted(managerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT account_id FROM manager WHERE id = ?", Long.class, managerId))
                .isEqualTo(계정_식별자("p3t2driver"));
    }

    /** 이미 다른 계정이 붙은 매니저 레코드는 재연결 대상이 아니다 — 통과시키면 남의 회차 명단이 넘어간다. */
    @Test
    @Sql(statements = {
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "(1, 'p3t2driver', 'x', 'P3T2대기기사', '010-0000-2001', 'driver', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2driver'), 1, 'driver', 'staff', "
                    + "'pending', now())"
    })
    void 이미_계정이_연결된_매니저를_다시_연결하면_409_ALREADY_LINKED_다() throws Exception {
        처리한다(요청_식별자("p3t2driver"), ACADEMY_A, "{\"accept\": true, \"link\": {\"manager_id\": 1}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_LINKED"));
    }

    /** 타 학원 매니저 식별자는 {@code 404 MANAGER_NOT_FOUND} 다 — 학생 쪽과 같은 이유로 존재를 노출하지 않는다. */
    @Test
    @Sql(statements = {
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "(1, 'p3t2driver', 'x', 'P3T2대기기사', '010-0000-2001', 'driver', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2driver'), 1, 'driver', 'staff', "
                    + "'pending', now())"
    })
    void 타_학원_매니저_id_로_연결을_시도하면_404_MANAGER_NOT_FOUND_다() throws Exception {
        처리한다(요청_식별자("p3t2driver"), ACADEMY_A, "{\"accept\": true, \"link\": {\"manager_id\": 5}}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));
    }

    /** 학생 계정은 {@code student.account_id} 를 채운다(AUTH-11) — 계정보다 먼저 만들어진 레코드에 붙는다. */
    @Test
    @Sql(statements = {
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "(1, 'p3t2student', 'x', 'P3T2대기학생', '010-0000-2003', 'student', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p3t2student'), 1, 'student', 'staff', "
                    + "'pending', now())"
    })
    void 학생_수락_시_student_레코드에_계정이_연결된다() throws Exception {
        처리한다(요청_식별자("p3t2student"), ACADEMY_A, 수락_본문(STUDENT_A1))
                .andExpect(status().isOk());

        반영한다();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT account_id FROM student WHERE id = ?", Long.class, STUDENT_A1))
                .isEqualTo(계정_식별자("p3t2student"));
    }

    /** 인가는 권한으로 판정한다 — {@code SIGNUP_APPROVE} 가 없는 학부모 계정은 {@code 403} 이다. */
    @Test
    void 관계자가_아닌_계정의_가입_요청_목록_조회는_403_이다() throws Exception {
        String parentToken = "Bearer "
                + tokenProvider.createAccessToken(5L, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/staff/signup-requests").header("Authorization", parentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 관계자_토큰(long academyId) {
        long accountId = academyId == ACADEMY_A ? STAFF_A_ACCOUNT : 3L;
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private ResultActions 처리한다(long requestId, long academyId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/signup-requests/" + requestId + "/decide")
                .header("Authorization", 관계자_토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String 수락_본문(long... studentIds) {
        StringBuilder ids = new StringBuilder();
        for (long id : studentIds) {
            ids.append(ids.isEmpty() ? "" : ", ").append(id);
        }
        return "{\"accept\": true, \"link\": {\"student_ids\": [%s]}}".formatted(ids);
    }

    private static String 거절_본문(String reason) {
        return "{\"accept\": false, \"reject_reason\": \"%s\"}".formatted(reason);
    }

    private String 계정_상태(long accountId) {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, accountId);
    }

    private String 요청_상태(long requestId) {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM signup_request WHERE id = ?", String.class, requestId);
    }

    private int 연결된_자녀_수(long accountId) {
        반영한다();
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
                WHERE g.account_id = ?
                """, Integer.class, accountId);
    }

    /** {@code @Sql} 로 심은 행의 자동 생성 키는 SQL 문에 적을 수 없어 되찾아 온다. */
    private long 요청_식별자(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT r.id FROM signup_request r JOIN account a ON a.id = r.account_id WHERE a.login_id = ?",
                Long.class, loginId);
    }

    private long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    private long 매니저_식별자(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM manager WHERE name = ?", Long.class, name);
    }
}
