package src.backend.student.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import src.backend.student.command.StudentCommandService;
import src.backend.student.query.StudentQueryService;

/**
 * 학생 컨트롤러의 역할 인가 슬라이스 테스트.
 * 이 컨트롤러는 메서드 레벨 인가가 하나도 없고 <b>클래스 레벨 하나가 엔드포인트 9개를 전부 지배</b>한다 —
 * 그래서 클래스 레벨 인가가 빠지면 학생 개인정보 전체가 조용히 열린다.
 * 관리자 2종의 허용(200)과 그 밖 역할의 거부(403)를 함께 고정한다.
 */
@WebMvcTest(StudentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class StudentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentCommandService studentCommandService;

    @MockitoBean
    private StudentQueryService studentQueryService;

    @Test
    void list_as_academy_admin_is_allowed() throws Exception {
        given(studentQueryService.list(any(), any(), anyBoolean())).willReturn(List.of());

        mockMvc.perform(get("/api/students?tenantId=1").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void list_as_platform_admin_is_allowed() throws Exception {
        given(studentQueryService.list(any(), any(), anyBoolean())).willReturn(List.of());

        mockMvc.perform(get("/api/students?tenantId=1").with(user("p").roles("PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 상세도 같은 클래스 레벨 인가를 탄다. 응답 본문은 관심사가 아니라 인가 통과만 본다. */
    @Test
    void detail_as_academy_admin_is_allowed() throws Exception {
        mockMvc.perform(get("/api/students/1").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void list_as_parent_returns_403() throws Exception {
        mockMvc.perform(get("/api/students?tenantId=1").with(user("p").roles("PARENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void detail_as_driver_returns_403() throws Exception {
        mockMvc.perform(get("/api/students/1").with(user("d").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_without_auth_returns_401() throws Exception {
        mockMvc.perform(get("/api/students?tenantId=1"))
                .andExpect(status().isUnauthorized());
    }
}
