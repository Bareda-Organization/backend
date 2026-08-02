package src.backend.sos.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;
import src.backend.sos.command.SosCommandService;
import src.backend.sos.query.SosQueryService;

/**
 * SOS 컨트롤러의 역할 인가 슬라이스 테스트.
 * 한 컨트롤러에서 학생·학부모·관리자 셋이 각자 다른 엔드포인트를 쓰는 구조라
 * 서로의 범위로 넘어가지 않는지를 함께 고정한다.
 * {@code myEvents_as_student_is_allowed} 는 self:read 권한이 애너테이션에서 부여표까지
 * 실제로 이어지는지 확인하는 유일한 허용 경로 케이스다.
 */
@WebMvcTest(SosController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class SosControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SosCommandService sosCommandService;

    @MockitoBean
    private SosQueryService sosQueryService;

    /** self:read 배선 실측 — 이 권한을 쓰는 3곳 중 여기만 허용 경로 테스트가 있다. */
    @Test
    void myEvents_as_student_is_allowed() throws Exception {
        given(sosQueryService.getMyEvents(any())).willReturn(List.of());

        mockMvc.perform(get("/api/sos-events/me").with(user("s").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void childrenEvents_as_parent_is_allowed() throws Exception {
        given(sosQueryService.getChildrenEvents(any())).willReturn(List.of());

        mockMvc.perform(get("/api/sos-events/children").with(user("p").roles("PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void tenantEvents_as_academy_admin_is_allowed() throws Exception {
        given(sosQueryService.getTenantEvents(any(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/sos-events?tenantId=1").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 관리자는 학원 전체 이력을 보지만 학생 개인 이력 엔드포인트에는 도달하지 못한다. */
    @Test
    void myEvents_as_academy_admin_returns_403() throws Exception {
        mockMvc.perform(get("/api/sos-events/me").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void childrenEvents_as_student_returns_403() throws Exception {
        mockMvc.perform(get("/api/sos-events/children").with(user("s").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantEvents_as_parent_returns_403() throws Exception {
        mockMvc.perform(get("/api/sos-events?tenantId=1").with(user("p").roles("PARENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void myEvents_without_auth_returns_401() throws Exception {
        mockMvc.perform(get("/api/sos-events/me"))
                .andExpect(status().isUnauthorized());
    }
}
