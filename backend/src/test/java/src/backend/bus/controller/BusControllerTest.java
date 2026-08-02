package src.backend.bus.controller;

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

import src.backend.bus.command.BusCommandService;
import src.backend.bus.query.BusQueryService;
import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;

/**
 * 버스 컨트롤러의 역할 인가 슬라이스 테스트.
 * 이 저장소에서 <b>클래스 레벨 인가와 메서드 레벨 인가가 공존하는 유일한 컨트롤러</b>라 별도로 고정한다 —
 * 클래스 기본값은 관리자 전용인데 {@code GET /me} 한 곳만 기사·선탑자로 덮어쓴다.
 * 특히 {@code myBus_as_academy_admin_returns_403} 이 "메서드 레벨이 클래스 레벨을 이긴다"(둘을 OR 로 합치지 않는다)는
 * 동작을 못 박는 케이스다. 이 둘이 합쳐지면 관리자가 {@code /me} 를 부를 수 있게 되어 조용히 권한이 넓어진다.
 */
@WebMvcTest(BusController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class BusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BusCommandService busCommandService;

    @MockitoBean
    private BusQueryService busQueryService;

    @Test
    void list_as_academy_admin_is_allowed() throws Exception {
        given(busQueryService.listBuses(any(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/buses?tenantId=1").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void list_as_platform_admin_is_allowed() throws Exception {
        given(busQueryService.listBuses(any(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/buses?tenantId=1").with(user("p").roles("PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 메서드 레벨 덮어쓰기가 기사에게 열려 있는지. 응답 본문은 이 슬라이스의 관심사가 아니라 인가 통과만 본다. */
    @Test
    void myBus_as_driver_is_allowed() throws Exception {
        mockMvc.perform(get("/api/buses/me").with(user("d").roles("DRIVER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void myBus_as_attendant_is_allowed() throws Exception {
        mockMvc.perform(get("/api/buses/me").with(user("a").roles("ATTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * 클래스 레벨(관리자)과 메서드 레벨(기사·선탑자)이 <b>합쳐지지 않는다</b>는 증거.
     * 합쳐지면 여기가 200 이 되면서 관리자에게 없던 권한이 생긴다.
     */
    @Test
    void myBus_as_academy_admin_returns_403() throws Exception {
        mockMvc.perform(get("/api/buses/me").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_as_driver_returns_403() throws Exception {
        mockMvc.perform(get("/api/buses?tenantId=1").with(user("d").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_without_auth_returns_401() throws Exception {
        mockMvc.perform(get("/api/buses?tenantId=1"))
                .andExpect(status().isUnauthorized());
    }
}
