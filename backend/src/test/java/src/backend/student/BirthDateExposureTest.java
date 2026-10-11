package src.backend.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학생 생년월일은 관계자 · 메인 관리자 화면에만 싣는다 — Ruling 883 · API_SPEC §6.3. 매니저 · 학부모 · 학생 앱이 받는 학생 응답에
 * 값도 키도 없어야 한다. 시드 학생 1 의 생년월일을 눈에 띄는 값으로 채우고, 앱 쪽 학생 응답 몇 곳의 본문을 훑는다.
 * 시드: 학부모 A1 = 계정 5(학생 1·2 연결) · 학생 A4 = 계정 10(학생 4) · 기사 A1 = 계정 13(회차 2 확정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BirthDateExposureTest {

    private static final String MARKER = "2011-02-03";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 학부모_학생_매니저_앱의_학생_응답에는_생년월일이_실리지_않는다() throws Exception {
        jdbcTemplate.update("UPDATE student SET birth_date = DATE '" + MARKER + "'");

        assertNoBirth(token(5L, Role.PARENT), "/api/v1/me/students");
        assertNoBirth(token(5L, Role.PARENT), "/api/v1/me");
        assertNoBirth(token(10L, Role.STUDENT), "/api/v1/me");
        assertNoBirth(token(13L, Role.DRIVER), "/api/v1/runs/2/roster");
        assertNoBirth(token(17L, Role.ESCORT), "/api/v1/runs/2/roster");
    }

    /** 관계자 상세에는 실린다 — 위 단언이 "어디에도 안 실리는 구현" 으로도 통과하지 않게 짝을 둔다. */
    @Test
    void 관계자_학생_상세에는_생년월일이_실린다() throws Exception {
        jdbcTemplate.update("UPDATE student SET birth_date = DATE '" + MARKER + "' WHERE id = 1");

        String body = mockMvc.perform(get("/api/v1/staff/students/1").header("Authorization", token(2L, Role.STAFF)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains(MARKER);
    }

    private void assertNoBirth(String bearer, String path) throws Exception {
        String body = mockMvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as(path).doesNotContain(MARKER).doesNotContainIgnoringCase("birth");
    }

    private String token(long accountId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, 1L, role, AccountStatus.ACTIVE);
    }
}
