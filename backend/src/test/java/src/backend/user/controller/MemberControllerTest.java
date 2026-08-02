package src.backend.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import src.backend.user.command.MemberCommandService;
import src.backend.user.dto.MemberDetailResponse;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.query.MemberQueryService;

/**
 * 구성원 관리 컨트롤러의 역할 인가 슬라이스 테스트.
 * 관리자 전용 — 학원 관리자(허용) 200, 학생·기사(권한 부족) 403 을 확인한다.
 * 비밀번호 재설정 응답에 평문이 실리지 않는다는 것(ApiResponse&lt;Void&gt;)도 여기서 못 박는다.
 */
@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class MemberControllerTest {

    private static final String BODY =
            "{\"email\":\"driver2@school.com\",\"password\":\"password\",\"name\":\"박기사\",\"role\":\"DRIVER\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberCommandService memberCommandService;

    @MockitoBean
    private MemberQueryService memberQueryService;

    @Test
    void register_as_academy_admin_is_allowed() throws Exception {
        given(memberCommandService.register(any(), any())).willReturn(member());

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

    @Test
    void detail_as_academy_admin_is_allowed() throws Exception {
        given(memberQueryService.get(any(), eq(3L), any())).willReturn(new MemberDetailResponse(
                3L, "driver2@school.com", "박기사", "010-2345-6789", null, Role.DRIVER, 1L,
                List.of(new MemberDetailResponse.BusRef(1L, "12가3456", false)),
                List.of()));

        mockMvc.perform(get("/api/members/3").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(3))
                .andExpect(jsonPath("$.data.assignedBuses[0].asAttendant").value(false));
    }

    @Test
    void update_as_academy_admin_is_allowed() throws Exception {
        given(memberCommandService.update(any(), eq(3L), any(), any())).willReturn(member());

        mockMvc.perform(patch("/api/members/3").with(user("a").roles("ACADEMY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"박기사\",\"phone\":\"010-2345-6789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("010-2345-6789"));
    }

    /** 응답 본문에 새 비밀번호가 실리면 안 된다 — data 는 null 이다. */
    @Test
    void resetPassword_as_academy_admin_returnsNoPasswordInBody() throws Exception {
        mockMvc.perform(patch("/api/members/3/password").with(user("a").roles("ACADEMY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpassword\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(memberCommandService).resetPassword(any(), eq(3L), any(), any());
    }

    /** 8자 미만은 @Size 로 400 — 서비스까지 가지 않는다. */
    @Test
    void resetPassword_shortPassword_returns_400() throws Exception {
        mockMvc.perform(patch("/api/members/3/password").with(user("a").roles("ACADEMY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void remove_as_academy_admin_is_allowed() throws Exception {
        mockMvc.perform(delete("/api/members/3").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(memberCommandService).removeMembership(any(), eq(3L), any());
    }

    @Test
    void remove_as_driver_returns_403() throws Exception {
        mockMvc.perform(delete("/api/members/3").with(user("d").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    private MemberResponse member() {
        return new MemberResponse(3L, "driver2@school.com", "박기사", "010-2345-6789", null, Role.DRIVER, 1L);
    }
}
