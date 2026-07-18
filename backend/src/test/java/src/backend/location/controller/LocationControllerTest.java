package src.backend.location.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationView;
import src.backend.location.service.spec.LocationService;

/**
 * 위치 컨트롤러의 역할 인가 슬라이스 테스트.
 * 미인증 → 401, 권한 없는 역할 → 403, 허용 역할 → 200 을 확인한다(DB 불필요).
 */
@WebMvcTest(LocationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class LocationControllerTest {

    private static final String BODY = "{\"lat\":37.5,\"lng\":127.0}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

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
        given(locationService.getChildrenLocations(any())).willReturn(List.of(sampleView()));

        mockMvc.perform(get("/api/locations/children").with(user("p").roles("PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentName").value("김민준"));
    }

    @Test
    void children_locations_as_student_returns_403() throws Exception {
        mockMvc.perform(get("/api/locations/children").with(user("s").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    private LocationView sampleView() {
        return new LocationView(1L, "김민준", 37.5, 127.0, LocalDateTime.now(), LocationOrigin.MOCK);
    }
}
