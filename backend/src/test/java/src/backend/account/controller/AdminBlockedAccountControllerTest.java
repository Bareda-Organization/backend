package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import src.backend.global.security.JwtTokenProvider;

/**
 * §6.10·§6.12 {@code /admin/blocked-accounts} — AUTH-06 · O-03 (목표 5).
 *
 * <p>단언 대상이 응답 본문이 아니라 <b>DB 행</b>인 것이 이 클래스의 핵심이다. 차단 해제는 응답에
 * 드러나지 않는 부수효과({@code failed_attempts} 초기화 · {@code audit_log} 적재)를 함께 내야 하고,
 * 그중 {@code failed_attempts} 는 로그인을 한 번만 성공시켜도 {@code recordLoginSuccess} 가 0 으로
 * 되돌려 <b>결함이 가려진다</b> — 그래서 로그인 경로를 타지 않고 직접 읽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminBlockedAccountControllerTest {

    /** 메인 관리자 계정({@code sysadmin}) — 시드의 유일한 {@code system_admin} 이며 해제 처리자로 기록된다. */
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

    private long blockedAccountId;

    @BeforeEach
    void 시드의_차단_계정을_찾는다() {
        blockedAccountId = 계정_식별자(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID);
    }

    /**
     * 목록은 {@code blocked} 계정을 소속 학원·시도 횟수·사유와 함께 싣는다(§6.10).
     *
     * <p>{@code academy_name} 까지 보는 이유는 이 화면이 전 학원 범위라 소속을 못 보면 운영자가 어느
     * 학원의 사고인지 판정할 수단이 부재하기 때문이다.
     */
    @Test
    void 차단_계정_목록에_blocked_상태_계정이_등장한다() throws Exception {
        String body = 차단_목록_본문();

        assertThat(로그인_아이디들(body))
                .as("시드의 차단 계정이 목록에 있어야 한다")
                .contains(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID);
        assertThat((String) 항목값(body, SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "academy_name")).isEqualTo("바래다학원 A");
        assertThat((Integer) 항목값(body, SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "failed_attempts")).isEqualTo(5);
        assertThat((String) 항목값(body, SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "reason")).isEqualTo("연속 로그인 실패 5회");
        assertThat((String) 항목값(body, SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "blocked_at")).isNotBlank();
    }

    /**
     * {@code blocked} 가 아닌 계정은 목록에 없다 — 상태 조건을 빠뜨린 구현이 전 계정을 실어 보내는 것을 막는다.
     *
     * <p>목록이 비어 있지 않다는 것도 함께 본다. 두 단언 중 하나만 두면 "항상 공집합" 인 구현이
     * 부재 단언만으로 통과한다.
     */
    @Test
    void 차단_계정_목록에_blocked_가_아닌_계정은_등장하지_않는다() throws Exception {
        List<String> loginIds = 로그인_아이디들(차단_목록_본문());

        assertThat(loginIds).as("차단 계정이 하나도 없으면 아래 부재 단언은 아무것도 검사하지 않는다").isNotEmpty();
        assertThat(loginIds)
                .as("active 계정이 차단 목록에 실렸다 — status='blocked' 조건이 빠졌다")
                .doesNotContain(SeedFixtures.DRIVER_A1_LOGIN_ID, SeedFixtures.STAFF_A_LOGIN_ID,
                        SeedFixtures.STUDENT_REJECTED_LOGIN_ID, SeedFixtures.PARENT_PENDING_LOGIN_ID);
    }

    /** 해제하면 계정이 {@code active} 로 돌아가고 그 계정으로 로그인이 성공한다(§6.12). */
    @Test
    void 해제하면_계정이_active_가_되고_그_계정으로_로그인이_200_이다() throws Exception {
        비밀번호를_아는_값으로_바꾼다(blockedAccountId);

        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + blockedAccountId + "/unblock")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}"
                                .formatted(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, RAW_PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * 해제 직후 {@code failed_attempts} 가 0 이다 — <b>로그인 경로를 타지 않고</b> 행을 직접 읽는다.
     *
     * <p>초기화를 빠뜨리면 상한(5)을 채운 카운터가 그대로 남아 <b>다음 1회 실패로 즉시 재차단</b>된다.
     * 그런데 "해제 후 로그인 200" 단언은 그대로 통과한다 — 로그인이 성공하는 순간
     * {@code Account.recordLoginSuccess} 가 카운터를 0 으로 돌려놓기 때문이다. 그래서 이 단언만은
     * 로그인을 거치지 않아야 검증력을 갖는다.
     */
    @Test
    void 해제_직후_failed_attempts_가_0_이다() throws Exception {
        assertThat(정수_컬럼(blockedAccountId, "failed_attempts"))
                .as("해제 전에는 상한값이 남아 있어야 이 테스트가 무언가를 검사한 것이 된다")
                .isEqualTo(5);

        해제한다(blockedAccountId);

        assertThat(정수_컬럼(blockedAccountId, "failed_attempts"))
                .as("해제하고도 카운터가 남으면 다음 1회 실패로 즉시 재차단된다")
                .isZero();
    }

    /** 해제 이력(처리자·일시)이 적재된다(§6.12 "이력 처리자·일시 저장") — 없으면 "누가 언제 풀었나" 를 판정할 수단이 부재. */
    @Test
    void 해제하면_unblocked_by_와_unblocked_at_이_적재된다() throws Exception {
        해제한다(blockedAccountId);
        동기화한다();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT unblocked_by FROM account WHERE id = ?", Long.class, blockedAccountId))
                .as("처리자는 요청 토큰의 메인 관리자 계정이어야 한다")
                .isEqualTo(SYSTEM_ADMIN_ACCOUNT_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unblocked_at FROM account WHERE id = ?", Object.class, blockedAccountId))
                .as("해제 일시가 비어 있다")
                .isNotNull();
    }

    /**
     * 해제는 {@code audit_log} 에 {@code action='unblock'} 행을 남긴다.
     *
     * <p>{@code §6.13 GET /admin/login-history}(Phase 14)가 이 행을 {@code block_event} 로 투영하므로,
     * 여기서 쌓지 않으면 그 화면이 성립하지 않는다. 조회 엔드포인트는 이 태스크 범위 밖이라 적재만 본다.
     */
    @Test
    void 해제하면_audit_log_에_action_unblock_행이_생긴다() throws Exception {
        int before = 감사_행_수(blockedAccountId);

        해제한다(blockedAccountId);
        동기화한다();

        assertThat(감사_행_수(blockedAccountId) - before)
                .as("차단 해제 1회에 감사 행 1개")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_account_id FROM audit_log WHERE action = 'unblock' AND target_id = ? "
                        + "ORDER BY id DESC LIMIT 1", Long.class, blockedAccountId))
                .as("행위자가 기록되지 않으면 감사 기록의 목적이 소멸한다")
                .isEqualTo(SYSTEM_ADMIN_ACCOUNT_ID);
    }

    /** 해제한 계정은 차단 목록에서 빠진다 — 목록과 해제가 같은 {@code status} 축을 보고 있다는 증거다. */
    @Test
    void 해제한_계정은_차단_계정_목록에서_사라진다() throws Exception {
        해제한다(blockedAccountId);

        assertThat(로그인_아이디들(차단_목록_본문()))
                .doesNotContain(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID);
    }

    /** {@code blocked} 가 아닌 계정의 해제 시도는 {@code 409 ACCOUNT_NOT_BLOCKED} 다(§6.12). */
    @Test
    void blocked_가_아닌_계정을_해제하려_하면_409_ACCOUNT_NOT_BLOCKED_다() throws Exception {
        long activeAccountId = 계정_식별자(SeedFixtures.DRIVER_A1_LOGIN_ID);

        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + activeAccountId + "/unblock")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_BLOCKED"));
    }

    /** 미존재 계정 지정은 {@code 404 ACCOUNT_NOT_FOUND} 다(§6.12). */
    @Test
    void 없는_계정을_해제하려_하면_404_ACCOUNT_NOT_FOUND_다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/blocked-accounts/99999999/unblock")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    /**
     * {@code ACCOUNT_UNBLOCK} 은 메인 관리자만 보유한다(FEATURE_SPEC §6.2).
     *
     * <p>이 단언이 없으면 학원 관계자가 <b>다른 학원</b>의 차단 계정을 풀 수 있게 되고, 그것은 격리
     * 위반이 아니라 격리 예외 구역 자체가 열리는 형태다.
     */
    @Test
    void 메인_관리자가_아닌_계정의_차단_해제는_403_이다() throws Exception {
        String staffToken = "Bearer " + tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/admin/blocked-accounts").header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + blockedAccountId + "/unblock")
                        .header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    private void 해제한다(long accountId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + accountId + "/unblock")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk());
    }

    private String 차단_목록_본문() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/blocked-accounts")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> 로그인_아이디들(String body) {
        return JsonPath.read(body, "$.data.items[*].login_id");
    }

    private <T> T 항목값(String body, String loginId, String field) {
        List<T> values = JsonPath.read(body, "$.data.items[?(@.login_id == '%s')].%s".formatted(loginId, field));
        return values.getFirst();
    }

    private long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    private int 정수_컬럼(long accountId, String column) {
        동기화한다();
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM account WHERE id = ?", Integer.class, accountId);
    }

    private int 감사_행_수(long targetAccountId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'unblock' AND target_id = ?",
                Integer.class, targetAccountId);
    }

    /**
     * 시드 계정의 비밀번호를 이 테스트가 아는 값으로 바꾼다 — 시드 해시가 어떤 평문을 담는지에
     * 기대면 시드 비밀번호를 바꾸는 순간 이 테스트가 이유 없이 실패한다.
     */
    private void 비밀번호를_아는_값으로_바꾼다(long accountId) {
        jdbcTemplate.update("UPDATE account SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode(RAW_PASSWORD), accountId);
    }

    /**
     * JPA 가 들고 있던 변경분을 DB 로 내보내고 1차 캐시를 비운다 — 이걸 빼면 아래 JDBC 조회가
     * 갱신 전 행을 읽어, 적재를 아예 하지 않는 구현도 통과한다.
     */
    private void 동기화한다() {
        entityManager.flush();
        entityManager.clear();
    }
}
