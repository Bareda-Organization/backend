package src.backend.tenant.controller;

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
import src.backend.tenant.dto.TenantResponse;
import src.backend.tenant.command.TenantCommandService;
import src.backend.tenant.query.TenantQueryService;

/**
 * 학원 생성 컨트롤러의 역할 인가 슬라이스 테스트.
 * 학원 생성은 플랫폼 관리자 전용 — 플랫폼 관리자(허용) 200, 학원 관리자(권한 부족) 403 을 확인한다.
 */
@WebMvcTest(TenantController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class TenantControllerTest {

    private static final String BODY = "{\"name\":\"한빛학원\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantCommandService tenantCommandService;

    @MockitoBean
    private TenantQueryService tenantQueryService;

    @Test
    void create_as_platform_admin_is_allowed() throws Exception {
        given(tenantCommandService.create(any())).willReturn(new TenantResponse(1L, "한빛학원", null, null));

        mockMvc.perform(post("/api/tenants").with(user("p").roles("PLATFORM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("한빛학원"));
    }

    @Test
    void create_as_academy_admin_returns_403() throws Exception {
        mockMvc.perform(post("/api/tenants").with(user("a").roles("ACADEMY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }
}
