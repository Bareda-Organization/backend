package src.backend.rideevent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
import src.backend.rideevent.command.RideEventCommandService;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.query.RideEventQueryService;

/**
 * 승하차 기록 컨트롤러의 역할 인가 슬라이스 테스트.
 * 미인증 → 401, 기사(권한 이관으로 제외) → 403, 선탑자(허용) → 200 을 확인한다.
 */
@WebMvcTest(RideEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class RideEventControllerTest {

    private static final String BODY = "{\"busId\":1,\"studentId\":1,\"type\":\"BOARD\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RideEventCommandService rideEventCommandService;

    @MockitoBean
    private RideEventQueryService rideEventQueryService;

    @Test
    void post_without_auth_returns_401() throws Exception {
        mockMvc.perform(post("/api/ride-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_as_student_returns_403() throws Exception {
        mockMvc.perform(post("/api/ride-events").with(user("s").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_as_driver_returns_403() throws Exception {
        mockMvc.perform(post("/api/ride-events").with(user("d").roles("DRIVER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_as_attendant_is_allowed() throws Exception {
        given(rideEventCommandService.record(any(), any())).willReturn(sampleResponse());

        mockMvc.perform(post("/api/ride-events").with(user("a").roles("ATTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("BOARD"));
    }

    private RideEventResponse sampleResponse() {
        return new RideEventResponse(1L, 1L, 1L, 1L, 1L, RideType.BOARD,
                LocalDateTime.now(), null, null, RideSource.MANUAL, null, null, null);
    }
}
