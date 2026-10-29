package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.Role;
import src.backend.global.location.GeoPoint;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * {@code GET /staff/routes/{id}/path} 의 Redis 캐시 — 같은 정차 목록의 두 번째 조회는 지도 호출을 하지 않고,
 * 정차 목록이 바뀌면 다시 계산하며, Redis 가 안 되면 캐시 없이 그대로 답한다. 지도 호출 횟수가 곧 일일 쿼터
 * 소비라 호출 횟수를 직접 센다. 실제 Redis 컨테이너를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RoutePathCacheTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A_ID = 2L;

    private static final List<GeoPoint> PATH = List.of(
            new GeoPoint(new BigDecimal("37.560000"), new BigDecimal("126.970000")),
            new GeoPoint(new BigDecimal("37.561000"), new BigDecimal("126.971000")));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private MapRouteClient mapRouteClient;

    @MockitoSpyBean
    private StringRedisTemplate redis;

    @BeforeEach
    void 지도_응답을_정한다() {
        reset(redis);
        when(mapRouteClient.route(any())).thenReturn(
                new RoadRoute(List.of(new RoadLeg(700, 100, PATH), new RoadLeg(500, 60, List.of())), false));
    }

    @Test
    void 같은_정차_목록의_두_번째_조회는_지도를_부르지_않고_같은_계산_시각을_돌려준다() throws Exception {
        long routeId = 편성한다("tue", "[1,2]");

        String first = 경로를_조회한다(routeId);
        String second = 경로를_조회한다(routeId);

        verify(mapRouteClient, times(1)).route(any());
        assertThat(JsonPath.<String>read(second, "$.data.computed_at"))
                .as("캐시에서 나온 경로의 computed_at 은 캐시된 경로를 계산한 시각이다")
                .isEqualTo(JsonPath.<String>read(first, "$.data.computed_at"));
        assertThat(JsonPath.<Integer>read(second, "$.data.distance_m")).isEqualTo(1200);
    }

    @Test
    void 정차_목록이_바뀌면_캐시를_건너뛰고_다시_계산한다() throws Exception {
        long routeId = 편성한다("mon", "[1,2]");
        경로를_조회한다(routeId);

        mockMvc.perform(patch("/api/v1/staff/routes/" + routeId).header("Authorization", 관계자_토큰())
                .contentType(MediaType.APPLICATION_JSON).content("{\"stop_ids\":[2,1,3]}"))
                .andExpect(status().isOk());
        경로를_조회한다(routeId);

        verify(mapRouteClient, times(2)).route(any());
    }

    @Test
    void 직선_근사_경로는_캐시하지_않아_다음_조회가_지도를_다시_부른다() throws Exception {
        when(mapRouteClient.route(any())).thenReturn(
                new RoadRoute(List.of(new RoadLeg(700, 100, PATH), new RoadLeg(500, 60, List.of())), true));
        long routeId = 편성한다("fri", "[1,2]");

        경로를_조회한다(routeId);
        경로를_조회한다(routeId);

        verify(mapRouteClient, times(2)).route(any());
    }

    @Test
    void Redis_가_안_되어도_경로를_지도로_계산해_답한다() throws Exception {
        long routeId = 편성한다("wed", "[1,2]");
        doAnswer(invocation -> {
            if (Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().contains("RoutePath"))) {
                throw new RedisConnectionFailureException("redis down");
            }
            return invocation.callRealMethod();
        }).when(redis).opsForValue();

        String first = 경로를_조회한다(routeId);
        경로를_조회한다(routeId);

        assertThat(JsonPath.<Integer>read(first, "$.data.distance_m")).isEqualTo(1200);
        verify(mapRouteClient, times(2)).route(any());
    }

    private String 경로를_조회한다(long routeId) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/routes/" + routeId + "/path").header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long 편성한다(String weekday, String stopIds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/staff/routes").header("Authorization", 관계자_토큰())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bus_id\":%d,\"weekday\":\"%s\",\"direction\":\"to_academy\",\"stop_ids\":%s}"
                        .formatted(BUS_A_ID, weekday, stopIds)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JsonPath.read(body, "$.data.id"));
    }

    private String 관계자_토큰() {
        return 토큰(tokenProvider, STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF);
    }
}
