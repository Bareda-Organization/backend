package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.geocoding.impl.StubGeocodingClient;

/**
 * §5.9 {@code GET /staff/stops/search} — 고정 노선 편성 화면의 <b>주소 검색</b>(2026-09-22 사용자 지시).
 *
 * <p>이 검색은 <b>아무것도 쓰지 않는다</b>. 관계자가 좌표를 눈으로 확인하고 고칠 기회를 갖기 전에
 * 승하차지가 생기면, 잘못 찍힌 지점이 그대로 남아 다음 편성에서 다시 후보로 뜬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffStopSearchControllerTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 도로명_주소를_좌표와_정규화된_주소로_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "테스트로 12")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lat").isNumber())
                .andExpect(jsonPath("$.data.lng").isNumber())
                .andExpect(jsonPath("$.data.display_name").value("테스트로 12"));
    }

    /**
     * 검색은 승하차지를 만들지 않는다 — 이 단언이 없으면 "검색했더니 목록에 생겼다" 를 아무도 못 잡는다.
     */
    @Test
    void 검색만으로는_승하차지가_생기지_않는다() throws Exception {
        int before = 승하차지_수();

        mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "새로운길 77")
                .header("Authorization", 관계자A_토큰())).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(승하차지_수()).isEqualTo(before);
    }

    /**
     * 50m 안에 이미 승하차지가 있으면 함께 알린다 — 관계자가 같은 자리에 둘째를 만들지 않게 한다
     * (근접 병합 임계는 {@code StopProximity.MERGE_RADIUS_METERS}, STU-05 와 같은 값).
     */
    @Test
    void 가까운_기존_승하차지를_거리와_함께_알린다() throws Exception {
        String body = mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "가까운길 33")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        double lat = com.jayway.jsonpath.JsonPath.read(body, "$.data.lat");
        double lng = com.jayway.jsonpath.JsonPath.read(body, "$.data.lng");
        jdbcTemplate.update("""
                INSERT INTO stop (academy_id, name, address, lat, lng, created_at, updated_at)
                VALUES (?, '이미 있는 자리', '가까운길 33', ?, ?, now(), now())
                """, ACADEMY_A_ID, lat, lng);

        mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "가까운길 33")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nearby[0].name").value("이미 있는 자리"))
                .andExpect(jsonPath("$.data.nearby[0].distance_m").isNumber());
    }

    /** 주소가 틀린 것과 공급자에 못 닿은 것은 다른 코드다 — 화면이 안내를 갈라야 한다. */
    @Test
    void 좌표로_옮길_수_없는_주소는_422_이고_공급자_장애는_503_이다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "번지없는도로명")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"));

        mockMvc.perform(get("/api/v1/staff/stops/search")
                        .param("address", StubGeocodingClient.UNAVAILABLE_MARKER + "로 1")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_UNAVAILABLE"));
    }

    // ── 자동완성(2026-09-23 사용자 지시 — 주소를 끝까지 치지 않아도 비슷한 주소를 알려 달라) ──

    /**
     * 번지 없이 도로명만 쳐도 후보 여럿이 나온다. 스텁은 번지 없는 입력을 "그 도로의 1·11·21번지"(서로
     * 110m 씩 떨어짐)로 넓혀 실 공급자의 부분 일치를 흉내 낸다 — 검색({@code /search})은 같은 입력에 {@code 422} 다.
     */
    @Test
    void 번지까지_치지_않아도_후보_여럿을_좌표와_함께_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/suggest").param("query", "후보도로")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].display_name").isString())
                .andExpect(jsonPath("$.data.items[0].lat").isNumber())
                .andExpect(jsonPath("$.data.items[0].nearby").isArray());
    }

    /** 자동완성에서 결과 0건은 오류가 아니다 — 입력 중에는 흔한 상태라 빈 목록이다(검색의 422 와 다르다). */
    @Test
    void 후보가_없으면_빈_목록이고_공급자_장애는_503_이다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/suggest").param("query", "!")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(get("/api/v1/staff/stops/suggest")
                        .param("query", StubGeocodingClient.UNAVAILABLE_MARKER + "로")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_UNAVAILABLE"));
    }

    /** 후보마다 그 자리 50m 안의 기존 승하차지를 싣는다 — 고른 뒤 다시 묻지 않아도 "이미 있다" 를 알린다. */
    @Test
    void 후보마다_가까운_기존_승하차지를_싣는다() throws Exception {
        String body = mockMvc.perform(get("/api/v1/staff/stops/search").param("address", "근처후보길 11")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        double lat = com.jayway.jsonpath.JsonPath.read(body, "$.data.lat");
        double lng = com.jayway.jsonpath.JsonPath.read(body, "$.data.lng");
        jdbcTemplate.update("""
                INSERT INTO stop (academy_id, name, address, lat, lng, created_at, updated_at)
                VALUES (?, '11번지 앞', '근처후보길 11', ?, ?, now(), now())
                """, ACADEMY_A_ID, lat, lng);

        mockMvc.perform(get("/api/v1/staff/stops/suggest").param("query", "근처후보길")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].nearby[0].name").value("11번지 앞"))
                .andExpect(jsonPath("$.data.items[2].nearby.length()").value(0));
    }

    private int 승하차지_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stop WHERE academy_id = ?", Integer.class,
                ACADEMY_A_ID);
    }

    private String 관계자A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
