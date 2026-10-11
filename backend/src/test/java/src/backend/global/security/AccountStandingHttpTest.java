package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.jayway.jsonpath.JsonPath;

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

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 권한을 잃은 계정의 access 토큰은 다음 요청부터 거절한다 — Ruling 880 · API_SPEC §1.11 {@code TOKEN_EXPIRED} · §2.6.
 *
 * <p>퇴사 · 차단 · 삭제된 매니저 세 경우를 각각 본다. 상태를 바꾸기 <b>전</b> 같은 토큰이 통과하는 것도 함께 확인한다 — 거절만 보면
 * "모든 요청을 거절" 하는 구현이 통과한다. 재발급은 같은 계정의 refresh 로 확인한다(401 이어야 클라이언트가 로그인으로 간다).
 *
 * <p>시드 계정(V2): 관계자 A = 계정 2, 기사 A1 = 계정 13(매니저 1), 학부모 A1 = 계정 5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountStandingHttpTest {

    private static final long STAFF_A_ACCOUNT = 2L;
    private static final long DRIVER_A1_ACCOUNT = 13L;
    private static final long PARENT_A1_ACCOUNT = 5L;
    private static final long ACADEMY_A = 1L;
    private static final String SEED_PASSWORD = "password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 퇴사한_관계자의_이미_받은_access_토큰은_다음_요청에서_401_TOKEN_EXPIRED_다() throws Exception {
        String token = bearer(STAFF_A_ACCOUNT, ACADEMY_A, Role.STAFF);
        callStaffApi(token).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE academy_staff SET status = 'inactive' WHERE account_id = ?", STAFF_A_ACCOUNT);

        callStaffApi(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void 차단된_계정의_이미_받은_access_토큰은_다음_요청에서_401_TOKEN_EXPIRED_다() throws Exception {
        String token = bearer(PARENT_A1_ACCOUNT, ACADEMY_A, Role.PARENT);
        callMe(token).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", PARENT_A1_ACCOUNT);

        callMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void 삭제된_매니저_계정의_이미_받은_access_토큰은_401_이고_재발급도_401_이다() throws Exception {
        MvcResult login = login("driverA1");
        String access = "Bearer " + JsonPath.read(body(login), "$.data.access_token");
        String refresh = JsonPath.read(body(login), "$.data.refresh_token");
        callMe(access).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE manager SET deleted_at = now() WHERE account_id = ?", DRIVER_A1_ACCOUNT);

        callMe(access).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /**
     * 사진 파일 서빙({@code GET /files/photos/**})도 같은 필터를 지난다 — 권한을 잃은 계정은 컨트롤러에 닿기 전에 401 이다.
     * 사진은 학생 개인정보(L3)라 필터가 이 경로에서 빠지면 차단된 계정이 이미 받은 토큰으로 사진을 계속 받는다.
     */
    @Test
    void 권한을_잃은_계정의_토큰은_사진_파일_경로에서도_401_TOKEN_EXPIRED_다() throws Exception {
        String token = bearer(STAFF_A_ACCOUNT, ACADEMY_A, Role.STAFF);
        callPhoto(token).andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("대조군 — 재직 중에는 401 이 아니다(없는 파일이라 404)").isNotEqualTo(401));

        jdbcTemplate.update("UPDATE academy_staff SET status = 'inactive' WHERE account_id = ?", STAFF_A_ACCOUNT);

        callPhoto(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /** 거절만 보면 "전부 거절" 구현이 통과한다 — 같은 학원의 다른 정상 계정은 그대로 통과해야 한다. */
    @Test
    void 다른_계정이_퇴사해도_정상_계정의_토큰은_통과한다() throws Exception {
        jdbcTemplate.update("UPDATE academy_staff SET status = 'inactive' WHERE account_id = ?", STAFF_A_ACCOUNT);

        callMe(bearer(PARENT_A1_ACCOUNT, ACADEMY_A, Role.PARENT)).andExpect(status().isOk());
        callMe(bearer(DRIVER_A1_ACCOUNT, ACADEMY_A, Role.DRIVER)).andExpect(status().isOk());
    }

    /** 로그아웃은 그 기기의 refresh 만 무효화한다 — 같은 계정의 다른 기기 access 는 끊지 않는다(Ruling 880). */
    @Test
    void 한_기기에서_로그아웃해도_같은_계정의_다른_기기_access_토큰은_통과한다() throws Exception {
        MvcResult deviceA = login("driverA1");
        MvcResult deviceB = login("driverA1");
        String accessA = "Bearer " + JsonPath.read(body(deviceA), "$.data.access_token");
        String refreshA = JsonPath.read(body(deviceA), "$.data.refresh_token");
        String accessB = "Bearer " + JsonPath.read(body(deviceB), "$.data.access_token");

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", accessA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"%s\"}".formatted(refreshA)))
                .andExpect(status().isNoContent());

        callMe(accessB).andExpect(status().isOk());
    }

    private ResultActions callMe(String bearer) throws Exception {
        return mockMvc.perform(get("/api/v1/me").header("Authorization", bearer));
    }

    private ResultActions callStaffApi(String bearer) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/managers").header("Authorization", bearer));
    }

    private ResultActions callPhoto(String bearer) throws Exception {
        return mockMvc.perform(get("/api/v1/files/photos/no-such-photo.jpg").header("Authorization", bearer));
    }

    private MvcResult login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, SEED_PASSWORD)))
                .andExpect(status().isOk()).andReturn();
    }

    private String bearer(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
