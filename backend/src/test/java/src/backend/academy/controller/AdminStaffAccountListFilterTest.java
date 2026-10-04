package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * R48 이 관계자 계정 목록에 더한 쿼리 {@code academy_id}·{@code q}·{@code status} 와 응답 {@code counts} ·
 * 항목 {@code academy_id} · {@code academy_pending_signup_count}(API_SPEC §6.6, Ruling 807).
 *
 * <p>시드 학원에도 관계자가 있어 건수 단언은 전부 {@code q=r48}(이 시험이 만든 계정만 걸리는 검색어)로 범위를 좁힌다.
 * 계정 구성 — 학원 A: 재직 1 · 학원 B: 재직 1 + 퇴사 1 · 학원 C: 퇴사 1 (정원이 1명이라 재직은 학원당 1명이다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStaffAccountListFilterTest {

    private static final String SCOPE_Q = "r48";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * {@code counts} 는 <b>{@code status} 만 뺀</b> 같은 조건의 건수다 — 탭 건수용이라 탭(상태)을 눌러도 바뀌지 않아야 한다.
     * 상태를 걸지 않은 호출과 {@code inactive} 를 건 호출이 같은 {@code counts}(재직 2 · 퇴사 2)를 돌려주고, 후자의 항목은 퇴사
     * 2건만이다. 상태 필터가 {@code counts} 에도 걸리면 재직이 0 으로 나온다.
     */
    @Test
    void counts_는_status_만_뺀_조건의_재직_퇴사_건수다() throws Exception {
        계정_구성();

        요청("q", SCOPE_Q)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(4))
                .andExpect(jsonPath("$.data.counts.active").value(2))
                .andExpect(jsonPath("$.data.counts.inactive").value(2));
        요청("q", SCOPE_Q, "status", "inactive")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(2))
                .andExpect(jsonPath("$.data.items[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("inactive"))))
                .andExpect(jsonPath("$.data.counts.active").value(2))
                .andExpect(jsonPath("$.data.counts.inactive").value(2));
    }

    /** {@code academy_id} 는 그 학원 관계자만 남기고, {@code counts} 도 그 학원 안의 건수로 좁아진다. */
    @Test
    void academy_id_는_그_학원_관계자만_남기고_counts_도_좁힌다() throws Exception {
        계정_구성();
        long academyB = 학원_식별자("R48SL0002");

        요청("q", SCOPE_Q, "academy_id", String.valueOf(academyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(2))
                .andExpect(jsonPath("$.data.counts.active").value(1))
                .andExpect(jsonPath("$.data.counts.inactive").value(1))
                .andExpect(jsonPath("$.data.items[*].academy_id").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(String.valueOf(academyB)))));
    }

    /** {@code q} 는 이름·로그인 아이디 양쪽에서 부분 일치하고 대소문자를 가리지 않는다 — 로그인 아이디는 대소문자 섞어 저장해 둔다. */
    @Test
    void q_는_이름과_로그인_아이디를_대소문자_무시로_부분_일치한다() throws Exception {
        계정_구성();

        요청("q", "R48SLAONLY")     // 로그인 아이디 r48slAonly 를 전부 대문자로 검색
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items[0].login_id").value("r48slAonly"));
        요청("q", "r48 씨")          // 이름 "R48 씨관계자" 의 일부 — 소문자로 검색
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("R48 씨관계자"));
    }

    /** 항목마다 소속 학원 식별자와 그 학원의 대기 중 관계자 가입 요청 수를 싣는다. */
    @Test
    void 항목은_academy_id_와_academy_pending_signup_count_를_싣는다() throws Exception {
        계정_구성();
        신청("r48slnew1", "R48SL0001");
        신청("r48slnew2", "R48SL0001");

        String body = 요청("q", SCOPE_Q).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Number> academyAPending = JsonPath.read(body,
                "$.data.items[?(@.login_id == 'r48slAonly')].academy_pending_signup_count");
        List<Number> academyBPending = JsonPath.read(body,
                "$.data.items[?(@.academy_name == '학원R48SL0002')].academy_pending_signup_count");
        List<String> academyAId = JsonPath.read(body, "$.data.items[?(@.login_id == 'r48slAonly')].academy_id");

        assertThat(academyAPending).hasSize(1);
        assertThat(academyAPending.get(0).longValue()).isEqualTo(2L);
        assertThat(academyBPending).hasSize(2).allSatisfy(count -> assertThat(count.longValue()).isZero());
        assertThat(academyAId).containsExactly(String.valueOf(학원_식별자("R48SL0001")));
    }

    /** 없는 학원을 지목하면 {@code 404 ACADEMY_NOT_FOUND}, 상태가 두 값 밖이면 {@code 422 VALIDATION_FAILED} 다. */
    @Test
    void 없는_academy_id_는_404_이고_status_가_값_밖이면_422_다() throws Exception {
        요청("academy_id", "999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
        요청("status", "retired")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private ResultActions 요청(String... keyValues) throws Exception {
        var request = get("/api/v1/admin/staff-accounts")
                .header("Authorization", "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN,
                        AccountStatus.ACTIVE))
                .param("size", "100");
        for (int i = 0; i < keyValues.length; i += 2) {
            request = request.param(keyValues[i], keyValues[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** 학원 A(재직 1) · B(재직 1 + 퇴사 1) · C(퇴사 1) — 재직 2 · 퇴사 2. */
    private void 계정_구성() {
        학원("R48SL0001");
        학원("R48SL0002");
        학원("R48SL0003");
        관계자("r48slAonly", "R48 에이관계자", "R48SL0001", "active");
        관계자("r48slBactive", "R48 비재직자", "R48SL0002", "active");
        관계자("r48slBgone", "R48 비퇴사자", "R48SL0002", "inactive");
        관계자("r48slCgone", "R48 씨관계자", "R48SL0003", "inactive");
    }

    private void 학원(String code) {
        jdbc.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, '서울', 'active')", code,
                "학원" + code);
    }

    private long 학원_식별자(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
        return id == null ? -1L : id;
    }

    private void 관계자(String loginId, String name, String academyCode, String staffStatus) {
        계정(loginId, name, academyCode, "active");
        jdbc.update("INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                + "((SELECT id FROM academy WHERE code = ?), (SELECT id FROM account WHERE login_id = ?), ?)",
                academyCode, loginId, staffStatus);
    }

    private void 계정(String loginId, String name, String academyCode, String status) {
        jdbc.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                + "((SELECT id FROM academy WHERE code = ?), ?, 'x', ?, ?, 'staff', ?)", academyCode, loginId, name,
                "010-4802-%04d".formatted(Math.abs(loginId.hashCode()) % 10000), status);
    }

    /** 신규 관계자 가입 신청 — 신청자 계정과 대기 중 요청 한 건 — 신청자는 아직 {@code academy_staff} 행이 없어 관계자 목록에 걸리지 않는다. */
    private void 신청(String loginId, String academyCode) {
        계정(loginId, "신청자" + loginId.hashCode(), academyCode, "pending");
        jdbc.update("INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, "
                + "requested_at) VALUES ((SELECT id FROM account WHERE login_id = ?), "
                + "(SELECT id FROM academy WHERE code = ?), 'staff', 'system_admin', 'pending', now())",
                loginId, academyCode);
    }
}
