package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.request.PageParams;
import src.backend.global.security.JwtTokenProvider;

/**
 * §6.6·§6.7 {@code /admin/staff-accounts} — ACAD-06 · O-02 (목표 7 · §6.7).
 *
 * <p><b>이 클래스의 최우선 단언은 refresh 토큰 무효화</b>다. 퇴사·비밀번호 초기화는 응답이 200 이라
 * 무효화를 빠뜨려도 어떤 응답 단언도 걸리지 않는다 — 그 상태에서는 퇴사한 관계자가 들고 있던 refresh
 * 토큰으로 access 토큰을 계속 새로 받고, 그러면 {@code RefreshCommandService} 가
 * {@code assertNotBlocked()} 를 생략한 근거(그 클래스 주석)가 거짓이 된다.
 *
 * <p>무효화 단언 옆에 <b>무효화하지 않아야 하는 경로</b>(이름만 수정)를 나란히 둔다 — 한쪽만 두면
 * "무슨 수정이든 전부 무효화" 하는 구현이 통과해, 관리자가 오탈자를 고칠 때마다 그 학원 관계자가
 * 로그아웃된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStaffAccountControllerTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    // ── §6.6 목록 ─────────────────────────────────────────────────────────

    /** 목록은 관계자 계정을 소속 학원 이름·재직 상태와 함께 싣는다(§6.6). */
    @Test
    void 관계자_계정_목록은_계정_정보와_학원_이름과_재직_상태를_함께_싣는다() throws Exception {
        String body = 목록_본문();

        assertThat(로그인_아이디들(body)).contains(SeedFixtures.STAFF_A_LOGIN_ID);
        assertThat((String) 항목값(body, SeedFixtures.STAFF_A_LOGIN_ID, "academy_name")).isEqualTo("바래다학원 A");
        assertThat((String) 항목값(body, SeedFixtures.STAFF_A_LOGIN_ID, "name")).isEqualTo("김운영");
        assertThat((String) 항목값(body, SeedFixtures.STAFF_A_LOGIN_ID, "status")).isEqualTo("active");
    }

    /**
     * 승인 전({@code academy_staff} 행이 아직 없는) 관계자 계정은 목록에 없다.
     *
     * <p>{@code role='staff'} 만 보고 목록을 만들면 승인 대기 계정이 함께 실려, 관리자가 <b>아직 어느
     * 학원에도 소속되지 않은</b> 계정을 퇴사·재직 전환하려 하게 된다. 그 축은 §6.4 승인 큐가 따로 맡는다.
     */
    @Test
    void 승인_전_관계자_계정은_목록에_없다() throws Exception {
        List<String> loginIds = 로그인_아이디들(목록_본문());

        assertThat(loginIds).as("목록이 비면 아래 부재 단언은 아무것도 검사하지 않는다").isNotEmpty();
        assertThat(loginIds).doesNotContain(SeedFixtures.STAFF_PENDING_LOGIN_ID);
    }

    /** 목록 응답은 §1.8 봉투를 그대로 쓴다 — 항목 배열과 페이지 정보 넷을 함께 싣는다. */
    @Test
    void 관계자_계정_목록_응답은_items_와_page_size_total_count_has_next_를_함께_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/staff-accounts").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(PageParams.DEFAULT_PAGE))
                .andExpect(jsonPath("$.data.size").value(PageParams.DEFAULT_SIZE))
                .andExpect(jsonPath("$.data.total_count").isNumber())
                .andExpect(jsonPath("$.data.has_next").isBoolean());
    }

    // ── 목표 7 · refresh 토큰 무효화 ──────────────────────────────────────

    /**
     * 퇴사 처리는 그 계정의 refresh 토큰을 전량 무효화한다(목표 7).
     *
     * <p>무효화하지 않으면 퇴사한 관계자가 기존 토큰으로 access 토큰을 계속 재발급받는다 —
     * 관계자 계정은 학생 개인정보 전체에 접근하므로(§6.7) 이것이 이 태스크에서 가장 값비싼 결함이다.
     */
    @Test
    void 관계자_계정을_inactive_로_바꾸면_그_계정의_기존_refresh_토큰이_401_TOKEN_EXPIRED_다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3rev1");
        String refreshToken = 로그인해서_refresh_를_받는다(staff.loginId());

        수정한다(staff.accountId(), "{\"status\":\"inactive\"}").andExpect(status().isOk());

        재발급을_시도한다(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /**
     * 비밀번호 초기화도 refresh 토큰을 전량 무효화한다(목표 7).
     *
     * <p>비밀번호 변경(§2.8)이 이미 같은 처리를 한다 — 초기화만 예외로 두면 관리자가 비밀번호를 새로
     * 발급한 뒤에도 옛 비밀번호로 얻은 세션이 그대로 살아 있어, 초기화의 목적(탈취 대응)이 소멸한다.
     */
    @Test
    void 비밀번호를_초기화하면_그_계정의_기존_refresh_토큰이_401_TOKEN_EXPIRED_다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3rev2");
        String refreshToken = 로그인해서_refresh_를_받는다(staff.loginId());

        수정한다(staff.accountId(), "{\"reset_password\":true}").andExpect(status().isOk());

        재발급을_시도한다(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /**
     * 접근 범위를 바꾸지 않는 수정은 세션을 끊지 않는다 — 위 두 단언의 짝이다.
     *
     * <p>이것이 없으면 "어떤 수정이든 무조건 전량 무효화" 하는 구현도 위 두 단언을 통과한다. 그
     * 구현에서는 관리자가 연락처 오타 하나를 고칠 때마다 운행 중인 관계자가 재로그인을 요구받는다.
     */
    @Test
    void 이름만_수정하면_기존_refresh_토큰은_그대로_재발급된다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3rev3");
        String refreshToken = 로그인해서_refresh_를_받는다(staff.loginId());

        수정한다(staff.accountId(), "{\"name\":\"이름만바꿈\"}").andExpect(status().isOk());

        재발급을_시도한다(refreshToken).andExpect(status().isOk());
    }

    // ── §6.7 수정 ─────────────────────────────────────────────────────────

    /** 퇴사 처리는 {@code academy_staff.status} 를 {@code inactive} 로 바꾼다 — 계정 상태(4종)는 건드리지 않는다. */
    @Test
    void 퇴사_처리하면_academy_staff_는_inactive_가_되고_account_status_는_그대로다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3sts1");

        수정한다(staff.accountId(), "{\"status\":\"inactive\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("inactive"));
        동기화한다();

        assertThat(재직_상태(staff.accountId())).isEqualTo("inactive");
        assertThat(계정_상태(staff.accountId()))
                .as("account.status 에는 'inactive' 값 자체가 부재하다(CHECK: pending·active·rejected·blocked)")
                .isEqualTo("active");
    }

    /** 퇴사한 관계자를 다시 재직으로 되돌릴 수 있다 — 정원이 비어 있으면 통과한다(Ruling 139). */
    @Test
    void 퇴사한_관계자를_active_로_되돌리면_다시_재직이_된다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3sts2");
        수정한다(staff.accountId(), "{\"status\":\"inactive\"}").andExpect(status().isOk());

        수정한다(staff.accountId(), "{\"status\":\"active\"}").andExpect(status().isOk());
        동기화한다();

        assertThat(재직_상태(staff.accountId())).isEqualTo("active");
    }

    /** 이미 재직 관계자가 있는 학원에 두 번째 재직자를 만들려 하면 {@code 409 STAFF_QUOTA_EXCEEDED} 다(§6.7). */
    @Test
    void 이미_active_관계자가_있는_학원의_다른_관계자를_active_로_전환하면_409_STAFF_QUOTA_EXCEEDED_다() throws Exception {
        관계자 재직자 = 관계자를_만든다("p3t3qta1");
        long 퇴사자 = 같은_학원에_퇴사한_관계자를_더한다(재직자.academyId(), "p3t3qta2");

        수정한다(퇴사자, "{\"status\":\"active\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STAFF_QUOTA_EXCEEDED"));
    }

    /**
     * 초기화 응답의 임시 비밀번호로 로그인되고, 옛 비밀번호로는 실패한다(§6.7).
     *
     * <p>둘을 함께 보는 이유는, 새 비밀번호를 응답에만 싣고 저장하지 않은 구현과 저장은 했는데 옛
     * 해시를 남겨 둔 구현이 각각 한쪽 단언만으로는 통과하기 때문이다.
     */
    @Test
    void 비밀번호_초기화_응답의_임시_비밀번호로_로그인하면_200_이고_기존_비밀번호로는_실패한다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3pwd1");

        String temporary = 임시_비밀번호(staff.accountId());

        assertThat(temporary).as("임시 비밀번호가 응답에 실리지 않았다").isNotBlank();
        로그인을_시도한다(staff.loginId(), temporary).andExpect(status().isOk());
        로그인을_시도한다(staff.loginId(), RAW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    /**
     * 같은 계정을 두 번 초기화해도 임시 비밀번호가 서로 다르다.
     *
     * <p>계정 식별자나 이름에서 유도하는 구현이면 두 값이 같아지고, 그러면 한 번이라도 임시 비밀번호를
     * 본 사람이 이후의 모든 초기화 결과를 예측한다.
     */
    @Test
    void 임시_비밀번호는_같은_계정에_두_번_초기화해도_서로_다르다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3pwd2");

        assertThat(임시_비밀번호(staff.accountId())).isNotEqualTo(임시_비밀번호(staff.accountId()));
    }

    /** 초기화하지 않은 수정의 응답에는 임시 비밀번호가 실리지 않는다 — 1회 반환이라 매번 실리면 그 값이 화면·로그에 남는다. */
    @Test
    void 초기화를_요청하지_않은_수정의_응답에는_임시_비밀번호가_부재한다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3pwd3");

        수정한다(staff.accountId(), "{\"name\":\"초기화없음\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporary_password").doesNotExist());
    }

    /** 이름·연락처·이메일 수정은 저장된 값을 바꾼다(§6.7). */
    @Test
    void 이름과_연락처와_이메일을_수정하면_저장된_값이_바뀐다() throws Exception {
        관계자 staff = 관계자를_만든다("p3t3prf1");

        수정한다(staff.accountId(),
                "{\"name\":\"고친이름\",\"phone\":\"010-7777-7777\",\"email\":\"fixed@example.com\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("고친이름"));
        동기화한다();

        assertThat(문자열_컬럼(staff.accountId(), "name")).isEqualTo("고친이름");
        assertThat(문자열_컬럼(staff.accountId(), "phone")).isEqualTo("010-7777-7777");
        assertThat(문자열_컬럼(staff.accountId(), "email")).isEqualTo("fixed@example.com");
    }

    /** 미존재 계정 지정은 {@code 404 ACCOUNT_NOT_FOUND} 다(§6.7). */
    @Test
    void 없는_계정을_수정하려_하면_404_ACCOUNT_NOT_FOUND_다() throws Exception {
        수정한다(99999999L, "{\"name\":\"없는계정\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    /** {@code STAFF_APPROVE} 는 메인 관리자만 보유한다(FEATURE_SPEC §6.2) — 관계자가 남의 계정을 만지지 못한다. */
    @Test
    void 메인_관리자가_아닌_계정의_관계자_계정_조회와_수정은_403_이다() throws Exception {
        String staffToken = "Bearer " + tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/admin/staff-accounts").header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/admin/staff-accounts/2")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"남의계정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /** 이 테스트가 직접 만든 관계자 — 시드 관계자는 학원당 정원을 이미 채우고 있어 재직 전환 단언에 쓸 수 없다. */
    private record 관계자(long academyId, long accountId, String loginId) {
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    /**
     * 학원 1곳 + 재직 관계자 1명을 SQL 로 만든다.
     *
     * <p>엔티티가 아니라 SQL 인 이유는 {@code account.status='active'} 로 시작해야 하는데 가입 승인
     * 전이(pending → active)를 만드는 것이 Task 2 소유라, 지금 그 경로를 부르면 이 테스트가 남의
     * 산출물에 묶이기 때문이다.
     */
    private 관계자 관계자를_만든다(String loginId) {
        String code = loginId.toUpperCase();
        jdbcTemplate.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, '서울', 'active')",
                code, "P3T3학원" + loginId);
        long academyId = jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'staff', 'active')",
                academyId, loginId, passwordEncoder.encode(RAW_PASSWORD), "관계자" + loginId, "010-0000-0000");
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        jdbcTemplate.update("INSERT INTO academy_staff (academy_id, account_id, status) VALUES (?, ?, 'active')",
                academyId, accountId);
        return new 관계자(academyId, accountId, loginId);
    }

    /** 같은 학원에 이미 퇴사한 관계자 1명을 더한다 — 정원 판정이 재직자만 세는지 보려면 두 상태가 함께 있어야 한다. */
    private long 같은_학원에_퇴사한_관계자를_더한다(long academyId, String loginId) {
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                        + "VALUES (?, ?, 'x', ?, '010-0000-0000', 'staff', 'active')",
                academyId, loginId, "퇴사자" + loginId);
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        jdbcTemplate.update("INSERT INTO academy_staff (academy_id, account_id, status) VALUES (?, ?, 'inactive')",
                academyId, accountId);
        return accountId;
    }

    private org.springframework.test.web.servlet.ResultActions 수정한다(long accountId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/staff-accounts/" + accountId)
                .header("Authorization", 메인관리자_토큰())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String 임시_비밀번호(long accountId) throws Exception {
        MvcResult result = 수정한다(accountId, "{\"reset_password\":true}")
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(본문(result), "$.data.temporary_password");
    }

    private org.springframework.test.web.servlet.ResultActions 로그인을_시도한다(String loginId, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
    }

    private String 로그인해서_refresh_를_받는다(String loginId) throws Exception {
        MvcResult result = 로그인을_시도한다(loginId, RAW_PASSWORD).andExpect(status().isOk()).andReturn();
        return JsonPath.read(본문(result), "$.data.refresh_token");
    }

    private org.springframework.test.web.servlet.ResultActions 재발급을_시도한다(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)));
    }

    private String 목록_본문() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/staff-accounts")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn();
        return 본문(result);
    }

    private List<String> 로그인_아이디들(String body) {
        return JsonPath.read(body, "$.data.items[*].login_id");
    }

    private <T> T 항목값(String body, String loginId, String field) {
        List<T> values = JsonPath.read(body, "$.data.items[?(@.login_id == '%s')].%s".formatted(loginId, field));
        return values.getFirst();
    }

    private String 재직_상태(long accountId) {
        return jdbcTemplate.queryForObject("SELECT status FROM academy_staff WHERE account_id = ?", String.class,
                accountId);
    }

    private String 계정_상태(long accountId) {
        return 문자열_컬럼(accountId, "status");
    }

    private String 문자열_컬럼(long accountId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM account WHERE id = ?", String.class, accountId);
    }

    /** JPA 의 미반영 변경분을 DB 로 내보내고 1차 캐시를 비운다 — 빼면 아래 JDBC 조회가 갱신 전 행을 읽는다. */
    private void 동기화한다() {
        entityManager.flush();
        entityManager.clear();
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
