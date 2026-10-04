package src.backend.routing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 고정 노선 도로 경로 응답의 {@code distance_m}·{@code duration_s}·{@code computed_at}(API_SPEC §5.9, Ruling 819) —
 * 도로 경로 API 가 돌려준 구간 값의 합이 실리고, 직선 근사이거나 도로 좌표가 빈 배열이면 거리·시간이 {@code null} 이다.
 * 지도 호출 결과를 시험이 정해야 해서 {@link MapRouteClient} 를 가짜 응답으로 바꾼 별도 클래스다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRoutePathMetricsTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드 학원 A 의 2호차 — 고정 노선이 걸려 있지 않아 요일·방향 조합을 비워 쓸 수 있다. */
    private static final long BUS_A_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private MapRouteClient mapRouteClient;

    private static final List<GeoPoint> PATH = List.of(new GeoPoint(new BigDecimal("37.560000"), new BigDecimal("126.970000")),
            new GeoPoint(new BigDecimal("37.561000"), new BigDecimal("126.971000")));

    @Test
    void 도로_경로면_구간_거리와_소요의_합과_계산_시각이_실린다() throws Exception {
        when(mapRouteClient.route(any())).thenReturn(
                new RoadRoute(List.of(new RoadLeg(700, 100, PATH), new RoadLeg(500, 60, List.of())), false));
        long routeId = 편성한다("sun", "[1,2]");

        mockMvc.perform(get("/api/v1/staff/routes/" + routeId + "/path").header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback_used").value(false))
                .andExpect(jsonPath("$.data.distance_m").value(1200))
                .andExpect(jsonPath("$.data.duration_s").value(160))
                .andExpect(jsonPath("$.data.computed_at").exists());
    }

    @Test
    void 직선_근사면_거리와_소요가_null_이다() throws Exception {
        when(mapRouteClient.route(any())).thenReturn(
                new RoadRoute(List.of(new RoadLeg(700, 100, PATH), new RoadLeg(500, 60, List.of())), true));
        long routeId = 편성한다("sat", "[1,2]");

        mockMvc.perform(get("/api/v1/staff/routes/" + routeId + "/path").header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback_used").value(true))
                .andExpect(jsonPath("$.data.distance_m").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.duration_s").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 도로_좌표가_빈_배열이면_거리와_소요가_null_이다() throws Exception {
        long routeId = 편성한다("fri", "[]");

        mockMvc.perform(get("/api/v1/staff/routes/" + routeId + "/path").header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.road_path.length()").value(0))
                .andExpect(jsonPath("$.data.distance_m").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.duration_s").value(org.hamcrest.Matchers.nullValue()));
    }

    private long 편성한다(String weekday, String stopIds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/staff/routes").header("Authorization", 토큰())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"weekday":"%s","direction":"to_academy","stop_ids":%s}"""
                        .formatted(BUS_A_ID, weekday, stopIds)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JsonPath.read(body, "$.data.id"));
    }

    private String 토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
