package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * R48 이 관계자 가입 요청 목록에 더한 {@code current_staff}(API_SPEC §6.4, Ruling 807) — 그 학원의 <b>재직</b>
 * 관계자 한 명. 정원이 1명이라 승인이 막힌 이유와 푸는 방법(그 사람 퇴사 처리)을 처리 화면에 보이려는 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminStaffSignupCurrentStaffTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    /** 재직 관계자가 있는 학원의 요청은 그 사람의 이름 · 로그인 아이디 · 마지막 로그인 시각을 싣는다. */
    @Test
    void 재직_관계자가_있는_학원의_요청은_current_staff_에_이름과_로그인_아이디와_마지막_로그인을_싣는다() throws Exception {
        학원("R48CS0001");
        계정("r48csboss", "현재관계자", "R48CS0001", "active", "2026-10-03 09:00:00+09");
        재직("r48csboss", "R48CS0001", "active");
        신청("r48csnew", "R48CS0001");

        Map<String, Object> current = 현재_관계자("R48CS0001");

        assertThat(current).containsEntry("name", "현재관계자").containsEntry("login_id", "r48csboss");
        assertThat(OffsetDateTime.parse((String) current.get("last_login_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-03T09:00:00+09:00").toInstant());
    }

    /** 퇴사한 관계자만 있거나 관계자가 없는 학원은 {@code null} 이다 — 퇴사자를 싣으면 "정원이 찼다" 고 잘못 읽힌다. */
    @Test
    void 퇴사한_관계자만_있거나_관계자가_없는_학원의_요청은_current_staff_가_null_이다() throws Exception {
        학원("R48CS0002");
        학원("R48CS0003");
        계정("r48csgone", "퇴사관계자", "R48CS0002", "active", null);
        재직("r48csgone", "R48CS0002", "inactive");
        신청("r48csnew2", "R48CS0002");
        신청("r48csnew3", "R48CS0003");

        assertThat(현재_관계자("R48CS0002")).isNull();
        assertThat(현재_관계자("R48CS0003")).isNull();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /** 학원 코드로 가입 요청 한 건을 골라 그 {@code current_staff} 를 읽는다 — 요청이 목록에 없으면 실패한다. */
    private Map<String, Object> 현재_관계자(String academyCode) throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/staff-signup-requests")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(1L, null,
                                Role.SYSTEM_ADMIN, AccountStatus.ACTIVE))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = JsonPath.read(body,
                "$.data.items[?(@.academy.code == '" + academyCode + "')]");
        assertThat(rows).as("대기 요청이 목록에 있어야 한다").hasSize(1);
        assertThat(rows.get(0)).as("current_staff 키는 항상 있다 — 없으면 null").containsKey("current_staff");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) rows.get(0).get("current_staff");
        return current;
    }

    private void 학원(String code) {
        jdbc.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, '서울', 'active')", code,
                "학원" + code);
    }

    private void 계정(String loginId, String name, String academyCode, String status, String lastLoginAt) {
        jdbc.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status, "
                + "last_login_at) VALUES ((SELECT id FROM academy WHERE code = ?), ?, 'x', ?, ?, 'staff', ?, "
                + "?::timestamptz)", academyCode, loginId, name,
                "010-4801-%04d".formatted(Math.abs(loginId.hashCode()) % 10000), status, lastLoginAt);
    }

    private void 재직(String loginId, String academyCode, String staffStatus) {
        jdbc.update("INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                + "((SELECT id FROM academy WHERE code = ?), (SELECT id FROM account WHERE login_id = ?), ?)",
                academyCode, loginId, staffStatus);
    }

    /** 신규 관계자 가입 신청 — 신청자 계정과 대기 중 요청 한 건을 만든다. */
    private void 신청(String loginId, String academyCode) {
        계정(loginId, "신청" + loginId, academyCode, "pending", null);
        jdbc.update("INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, "
                + "requested_at) VALUES ((SELECT id FROM account WHERE login_id = ?), "
                + "(SELECT id FROM academy WHERE code = ?), 'staff', 'system_admin', 'pending', now())",
                loginId, academyCode);
    }
}
