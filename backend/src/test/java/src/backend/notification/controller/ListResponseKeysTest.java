package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 쪽 나누기 목록 응답 셋의 JSON 키 — 학생 목록 · 내 알림 목록 · 관계자 알림 로그가 공통 쪽 필드(items · page · size · total_count · has_next)에
 * 자기 항목(summary · unread_count · unacked_count)을 더한 그대로 나간다. 응답 레코드를 공통 쪽 봉투를 재사용하게 고쳐도 키 집합은 변하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListResponseKeysTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 학생_목록_응답의_키() throws Exception {
        assertThat(키("/api/v1/staff/students", 토큰(tokenProvider, 2L, ACADEMY_A, Role.STAFF)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "summary");
    }

    @Test
    void 내_알림_목록_응답의_키() throws Exception {
        assertThat(키("/api/v1/notifications", 토큰(tokenProvider, 5L, ACADEMY_A, Role.PARENT)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "unread_count");
    }

    @Test
    void 관계자_알림_로그_응답의_키() throws Exception {
        assertThat(키("/api/v1/staff/notifications", 토큰(tokenProvider, 2L, ACADEMY_A, Role.STAFF)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "unacked_count");
    }

    private java.util.Set<String> 키(String url, String token) throws Exception {
        String body = mockMvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(body, "$.data");
        return data.keySet();
    }
}
