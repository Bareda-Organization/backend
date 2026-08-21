package src.backend.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.security.JwtAuthenticationFilter;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;
import src.backend.location.command.BusLocationCommandService;
import src.backend.location.command.LocationCommandService;
import src.backend.location.dto.BusLocationReportRequest;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.dto.LocationView;
import src.backend.location.query.BusLocationQueryService;
import src.backend.location.query.LocationQueryService;

/**
 * 위치 컨트롤러의 역할 인가 슬라이스 테스트.
 * 미인증 → 401, 권한 없는 역할 → 403, 허용 역할 → 200 을 확인한다(DB 불필요).
 * 여기에 더해 요청 본문의 좌표 출처(origin)가 파싱 단계를 그대로 통과하는지도 확인한다(MON-2) —
 * 저장·조회까지의 왕복은 {@code location.command.BusLocationCommandServiceTest}가 맡는다.
 */
@WebMvcTest(LocationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class LocationControllerTest {

    private static final String BODY = "{\"lat\":37.5,\"lng\":127.0}";
    private static final String BUS_BODY = "{\"busId\":1,\"lat\":37.5,\"lng\":127.0}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationCommandService locationCommandService;

    @MockitoBean
    private LocationQueryService locationQueryService;

    @MockitoBean
    private BusLocationCommandService busLocationCommandService;

    @MockitoBean
    private BusLocationQueryService busLocationQueryService;

    @Test
    void report_without_auth_returns_401() throws Exception {
        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void report_as_parent_returns_403() throws Exception {
        mockMvc.perform(post("/api/locations").with(user("p").roles("PARENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void report_as_student_is_allowed() throws Exception {
        mockMvc.perform(post("/api/locations").with(user("s").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void children_locations_as_parent_is_allowed() throws Exception {
        given(locationQueryService.getChildrenLocations(any())).willReturn(List.of(sampleView()));

        mockMvc.perform(get("/api/locations/children").with(user("p").roles("PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentName").value("김민준"));
    }

    @Test
    void children_locations_as_student_returns_403() throws Exception {
        mockMvc.perform(get("/api/locations/children").with(user("s").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportBus_without_auth_returns_401() throws Exception {
        mockMvc.perform(post("/api/locations/bus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BUS_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportBus_as_parent_returns_403() throws Exception {
        mockMvc.perform(post("/api/locations/bus").with(user("p").roles("PARENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BUS_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportBus_as_driver_is_allowed() throws Exception {
        mockMvc.perform(post("/api/locations/bus").with(user("d").roles("DRIVER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BUS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * 하위호환 고정 — 기사 앱은 지금도 {busId, lat, lng} 만 보낸다.
     * origin 을 필수(@NotNull)로 만들면 이 요청이 400 이 되어 기사 앱이 즉시 죽는다.
     */
    @Test
    void reportBus_withoutOrigin_defaultsToGps() throws Exception {
        mockMvc.perform(post("/api/locations/bus").with(user("d").roles("DRIVER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BUS_BODY))
                .andExpect(status().isOk());

        assertThat(capturedBusRequest().origin()).isEqualTo(LocationOrigin.GPS);
    }

    /** 단말이 보낸 출처가 요청 본문 파싱 단계에서 그대로 살아 서비스까지 도달한다. */
    @Test
    void reportBus_withMockOrigin_isPassedThrough() throws Exception {
        mockMvc.perform(post("/api/locations/bus").with(user("d").roles("DRIVER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":1,\"lat\":37.5,\"lng\":127.0,\"origin\":\"MOCK\"}"))
                .andExpect(status().isOk());

        assertThat(capturedBusRequest().origin()).isEqualTo(LocationOrigin.MOCK);
    }

    /** 학생 보고 경로도 같은 규칙 — 생략은 GPS, 보낸 값은 그대로. */
    @Test
    void report_originIsOptionalAndPassedThrough() throws Exception {
        mockMvc.perform(post("/api/locations").with(user("s").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/locations").with(user("s").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":37.5,\"lng\":127.0,\"origin\":\"MOCK\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocationReportRequest> captor = ArgumentCaptor.forClass(LocationReportRequest.class);
        verify(locationCommandService, times(2)).reportSelf(any(), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(LocationReportRequest::origin)
                .containsExactly(LocationOrigin.GPS, LocationOrigin.MOCK);
    }

    @Test
    void tenantBusLocations_as_driver_returns_403() throws Exception {
        mockMvc.perform(get("/api/locations/buses").with(user("d").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantBusLocations_as_admin_is_allowed() throws Exception {
        given(busLocationQueryService.getTenantBusLocations(any(), any())).willReturn(List.of(sampleBusView()));

        mockMvc.perform(get("/api/locations/buses").with(user("a").roles("ACADEMY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].busName").value("3호차"));
    }

    private BusLocationReportRequest capturedBusRequest() {
        ArgumentCaptor<BusLocationReportRequest> captor = ArgumentCaptor.forClass(BusLocationReportRequest.class);
        verify(busLocationCommandService).reportSelf(any(), captor.capture());
        return captor.getValue();
    }

    private LocationView sampleView() {
        return new LocationView(1L, "김민준", 37.5, 127.0, LocalDateTime.now(), LocationOrigin.MOCK);
    }

    private BusLocationView sampleBusView() {
        return new BusLocationView(1L, "3호차", 37.5, 127.0, LocalDateTime.now(), LocationOrigin.MOCK);
    }
}
