package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;
import static testsupport.auth.TestTokens.메인관리자_토큰;
import static testsupport.web.TestResponses.본문;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 목록 응답 넷(학원 · 관계자 계정 · 가입 요청 · 매니저)의 {@code data} 최상위 키 — 공통 페이지 봉투의 다섯 키
 * ({@code items} · {@code page} · {@code size} · {@code total_count} · {@code has_next})에 그 목록만의 요약 키 하나가 붙는다
 * (API_SPEC §1.8). 웹 · 모바일 계약 검사가 이 키들을 그대로 읽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListResponseKeysTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 학원_목록은_봉투_키와_summary_를_싣는다() throws Exception {
        assertThat(키들("/api/v1/admin/academies", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "summary");
    }

    @Test
    void 관계자_계정_목록은_봉투_키와_counts_를_싣는다() throws Exception {
        assertThat(키들("/api/v1/admin/staff-accounts", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "counts");
    }

    @Test
    void 가입_요청_목록은_봉투_키와_pending_count_를_싣는다() throws Exception {
        assertThat(키들("/api/v1/staff/signup-requests", 토큰(tokenProvider, 2L, 1L, Role.STAFF)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "pending_count");
    }

    @Test
    void 매니저_목록은_봉투_키와_counts_를_싣는다() throws Exception {
        assertThat(키들("/api/v1/staff/managers", 토큰(tokenProvider, 2L, 1L, Role.STAFF)))
                .containsExactlyInAnyOrder("items", "page", "size", "total_count", "has_next", "counts");
    }

    private java.util.Set<String> 키들(String path, String token) throws Exception {
        String body = 본문(mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
                .andReturn());
        Map<String, Object> data = JsonPath.read(body, "$.data");
        return data.keySet();
    }
}
