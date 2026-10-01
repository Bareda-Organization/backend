package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 임시 비밀번호 강제 변경(API_SPEC §1.4 · §2.5 · §2.8 · §2.10, Ruling 540) — 관리자가 초기화한 계정은 비밀번호를
 * 바꿀 때까지 변경 외의 API 를 쓰지 못한다.
 *
 * <p>초기화 경로는 둘(§5.22 관계자→학부모·학생·매니저 · §6.7 메인 관리자→관계자)이고 둘 다 표식을 세운다.
 * 본인 변경만 표식을 내린다 — 표식을 내리는 자리가 하나라야 "초기화 직후 임시 값 그대로 쓰는" 계정이 남지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TemporaryPasswordForcedChangeTest {

    /** 시드 관계자 A(학원 A) 계정. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 관계자가_초기화한_계정은_임시_비밀번호_로그인_응답과_me_에_변경_필요_표식이_실린다() throws Exception {
        String temporaryPassword = 학부모_A1_초기화();

        String accessToken = 로그인(SeedFixtures.PARENT_A1_LOGIN_ID, temporaryPassword, true);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.must_change_password").value(true));
    }

    @Test
    void 초기화하지_않은_계정의_로그인_응답과_me_에는_표식이_꺼져_있다() throws Exception {
        String accessToken = 로그인(SeedFixtures.PARENT_A1_LOGIN_ID, "password", false);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.must_change_password").value(false));
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void 표식이_있는_동안은_비밀번호_변경과_me_와_로그아웃_외_API_가_403_PASSWORD_CHANGE_REQUIRED_다() throws Exception {
        String accessToken = 로그인(SeedFixtures.PARENT_A1_LOGIN_ID, 학부모_A1_초기화(), true);
        String bearer = "Bearer " + accessToken;

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));

        // 허용 3개는 게이트(403)를 지나 자기 판정에 닿는다 — 현재 비밀번호 불일치 401 · 쿠키·본문 토큰 부재 401.
        mockMvc.perform(post("/api/v1/auth/password").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"틀린값\",\"new_password\":\"newPassw0rd!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void refresh_로_새로_받은_access_토큰도_표식이_이어진다() throws Exception {
        String temporaryPassword = 학부모_A1_초기화();
        String loginBody = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(로그인_본문(SeedFixtures.PARENT_A1_LOGIN_ID, temporaryPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String refreshToken = JsonPath.read(loginBody, "$.data.refresh_token");

        String refreshBody = mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String refreshedAccess = JsonPath.read(refreshBody, "$.data.access_token");

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + refreshedAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void 본인이_비밀번호를_바꾸면_표식이_내려가고_새_로그인은_막히지_않는다() throws Exception {
        String temporaryPassword = 학부모_A1_초기화();
        String accessToken = 로그인(SeedFixtures.PARENT_A1_LOGIN_ID, temporaryPassword, true);

        mockMvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"%s\",\"new_password\":\"newPassw0rd!\"}"
                                .formatted(temporaryPassword)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM account WHERE id = ?",
                Boolean.class, 계정_식별자(SeedFixtures.PARENT_A1_LOGIN_ID)))
                .as("본인 변경은 표식을 내린다 — 안 내리면 바꾼 뒤에도 영원히 변경 화면에 갇힌다").isFalse();
        String freshToken = 로그인(SeedFixtures.PARENT_A1_LOGIN_ID, "newPassw0rd!", false);
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
    }

    @Test
    void 메인_관리자가_관계자_비밀번호를_초기화해도_표식이_선다() throws Exception {
        String adminToken = "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);

        String body = mockMvc.perform(patch("/api/v1/admin/staff-accounts/" + STAFF_A_ACCOUNT_ID)
                        .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reset_password\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String temporaryPassword = JsonPath.read(body, "$.data.temporary_password");

        로그인(SeedFixtures.STAFF_A_LOGIN_ID, temporaryPassword, true);
    }

    /** 관계자 A 가 학부모 A1 의 비밀번호를 초기화하고 임시 비밀번호 원문을 돌려받는다. */
    private String 학부모_A1_초기화() throws Exception {
        String staffToken = "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, 1L, Role.STAFF,
                AccountStatus.ACTIVE);
        String body = mockMvc.perform(post("/api/v1/staff/accounts/" + 계정_식별자(SeedFixtures.PARENT_A1_LOGIN_ID)
                        + "/password-reset").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.temporary_password");
    }

    /** 로그인하고 응답의 표식이 기대값인지 본 뒤 access 토큰을 돌려준다. */
    private String 로그인(String loginId, String password, boolean expectMustChange) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(로그인_본문(loginId, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.must_change_password").value(expectMustChange))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.access_token");
    }

    private static String 로그인_본문(String loginId, String password) {
        return "{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password);
    }

    private long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }
}
