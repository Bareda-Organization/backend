package src.backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.auth.command.AuthCommandService;
import src.backend.auth.dto.TokenResponse;
import src.backend.auth.query.AuthQueryService;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;

/**
 * 인증 컨트롤러 웹 슬라이스 테스트. /api/auth/** 가 공개(permitAll)라 토큰 없이 호출되며,
 * 유효성 검증(400)과 정상 응답(200)을 확인한다.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthCommandService authCommandService;

    @MockitoBean
    private AuthQueryService authQueryService;

    @Test
    void login_returns_tokens() throws Exception {
        given(authQueryService.login(any())).willReturn(new TokenResponse("access-x", "refresh-y"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"driver@school.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access-x"));
    }

    @Test
    void login_validation_fails_without_password() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"driver@school.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signup_asAcademyAdmin_returns_403() throws Exception {
        willThrow(new BusinessException(ErrorCode.FORBIDDEN, "관리자가 부여하는 역할은 회원가입으로 만들 수 없습니다"))
                .given(authCommandService).signup(any());

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"evil@school.com\",\"password\":\"password\",\"name\":\"공격자\",\"tenantId\":1,\"role\":\"ACADEMY_ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void signup_passwordShorterThanMinimum_returns400() throws Exception {
        String body = """
                {"email":"new@school.com","password":"short7c","name":"김하늘","tenantId":1,"role":"PARENT"}
                """;

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
