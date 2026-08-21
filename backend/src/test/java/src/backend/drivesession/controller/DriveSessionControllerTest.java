package src.backend.drivesession.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import src.backend.drivesession.command.DriveSessionCommandService;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.query.DriveSessionQueryService;
import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;

/**
 * 운행 세션 컨트롤러의 역할 인가 슬라이스 테스트.
 * 선탑자 명단 화면 사슬(bus/{busId} → {id}/roster)이 ATTENDANT 에게 열렸는지와,
 * 운행 시작은 여전히 DRIVER 전용인지(D-J)를 확인한다.
 */
@WebMvcTest(DriveSessionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DriveSessionControllerTest {

    private static final String START_BODY = "{\"busId\":1,\"direction\":\"PICKUP\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveSessionCommandService driveSessionCommandService;

    @MockitoBean
    private DriveSessionQueryService driveSessionQueryService;

    @Test
    void busHistory_as_attendant_is_allowed() throws Exception {
        // status 를 안 주면 컨트롤러가 status=null 로 서비스를 호출해야 한다(하위 호환 — 전체 이력).
        given(driveSessionQueryService.getBusHistory(any(), eq(1L), eq(null))).willReturn(List.of());

        mockMvc.perform(get("/api/drive-sessions/bus/1").with(user("a").roles("ATTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void busHistory_withStatusParam_passesStatusToService() throws Exception {
        // BG-7 — status=IN_PROGRESS 쿼리 파라미터가 그대로 서비스까지 전달되는지 확인.
        given(driveSessionQueryService.getBusHistory(any(), eq(1L), eq(DriveSessionStatus.IN_PROGRESS)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/drive-sessions/bus/1").queryParam("status", "IN_PROGRESS")
                        .with(user("a").roles("ATTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void busHistory_as_unassignedCrew_stillReturns403() throws Exception {
        // status 파라미터 추가가 기존 CanReadAssignedBus 인가를 우회하지 않는지 확인(권한은 컨트롤러 진입 전 애너테이션에서 걸린다).
        mockMvc.perform(get("/api/drive-sessions/bus/1").queryParam("status", "IN_PROGRESS")
                        .with(user("a").roles("PARENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantHistory_withStatusParam_passesStatusToService() throws Exception {
        // BG-7 — 관리자 엔드포인트도 status 쿼리 파라미터가 서비스까지 전달되는지 확인(기사 쪽과 대응).
        given(driveSessionQueryService.getTenantHistory(any(), eq(1L), eq(DriveSessionStatus.IN_PROGRESS)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/drive-sessions").queryParam("tenantId", "1")
                        .queryParam("status", "IN_PROGRESS")
                        .with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void tenantHistory_withoutStatus_passesNullToService_backwardCompatible() throws Exception {
        // status 를 안 주면 컨트롤러가 status=null 로 서비스를 호출해야 한다(하위 호환 — 전체 이력).
        given(driveSessionQueryService.getTenantHistory(any(), eq(1L), eq(null))).willReturn(List.of());

        mockMvc.perform(get("/api/drive-sessions").queryParam("tenantId", "1")
                        .with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void tenantHistory_as_nonAdmin_returns403() throws Exception {
        // status 파라미터 추가가 CanMonitorOperations 인가를 우회하지 않는지 확인.
        mockMvc.perform(get("/api/drive-sessions").queryParam("status", "IN_PROGRESS")
                        .with(user("a").roles("ATTENDANT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void roster_as_attendant_is_allowed() throws Exception {
        given(driveSessionQueryService.getRoster(any(), eq(1L))).willReturn(List.of());

        mockMvc.perform(get("/api/drive-sessions/1/roster").with(user("a").roles("ATTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void start_as_attendant_returns_403() throws Exception {
        mockMvc.perform(post("/api/drive-sessions/start").with(user("a").roles("ATTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isForbidden());
    }
}
