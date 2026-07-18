package src.backend.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.service.spec.MemberService;

/**
 * 구성원 등록 컨트롤러의 역할 인가 슬라이스 테스트.
 * 관리자 전용 — 학원 관리자(허용) 200, 학생(권한 부족) 403 을 확인한다.
 */
@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class MemberControllerTest {

    private static final String BODY =
            "{\"email\":\"driver2@school.com\",\"password\":\"password\",\"name\":\"박기사\",\"role\":\"DRIVER\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    void register_as_academy_admin_is_allowed() throws Exception {
        given(memberService.register(any(), any()))
                .willReturn(new MemberResponse(1L, "driver2@school.com", "박기사", Role.DRIVER, 1L));

        mockMvc.perform(post("/api/members").with(user("a").roles("ACADEMY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("DRIVER"));
    }

    @Test
    void register_as_student_returns_403() throws Exception {
        mockMvc.perform(post("/api/members").with(user("s").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }
}
