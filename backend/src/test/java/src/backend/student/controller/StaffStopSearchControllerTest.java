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
import src.backend.student.geocoding.impl.StubPlaceSearchClient;

/**
 * §5.9 {@code GET /staff/stops/suggest} — 고정 노선 편성 화면의 <b>주소 자동완성</b>(2026-09-23 사용자 지시).
 * 주소 한 건 검색({@code GET /staff/stops/search})은 화면이 쓰지 않아 R46-BE 가 삭제했다(Ruling 410).
 *
 * <p>이 조회는 <b>아무것도 쓰지 않는다</b>. 관계자가 좌표를 눈으로 확인하고 고칠 기회를 갖기 전에
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

    // ── 자동완성(2026-09-23 사용자 지시 — 주소를 끝까지 치지 않아도 비슷한 주소를 알려 달라) ──

    /**
     * 번지 없이 도로명만 쳐도 후보 여럿이 나온다. 스텁은 번지 없는 입력을 "그 도로의 1·11·21번지"(서로
     * 110m 씩 떨어짐)로 넓혀 실 공급자의 부분 일치를 흉내 낸다 — 
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
        String body = mockMvc.perform(get("/api/v1/staff/stops/suggest").param("query", "근처후보길 11")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        double lat = com.jayway.jsonpath.JsonPath.read(body, "$.data.items[0].lat");
        double lng = com.jayway.jsonpath.JsonPath.read(body, "$.data.items[0].lng");
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

    // ── 장소 검색(2026-09-23 — NAVER API HUB 지역 검색) ─────────────────────────────────────

    /**
     * 장소 이름(예: "목동 현대백화점")은 지오코딩이 못 찾는다 — 장소 검색 후보를 <b>앞에</b> 싣고 장소 이름을
     * 함께 준다(표시명 기본값). 스텁은 {@value StubPlaceSearchClient#PLACE_MARKER} 가 든 입력에만 장소를 낸다.
     */
    @Test
    void 장소_검색_후보를_주소_후보보다_앞에_장소_이름과_함께_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/suggest").param("query", StubPlaceSearchClient.PLACE_MARKER + "길")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].place_name").value(StubPlaceSearchClient.PLACE_MARKER + "길 본점"))
                .andExpect(jsonPath("$.data.items[1].place_name").doesNotExist());
    }

    /** 장소 검색은 보조 후보다 — 그쪽이 죽어도 주소 후보는 그대로 나온다(자동완성 전체를 503 으로 막지 않는다). */
    @Test
    void 장소_검색이_죽어도_주소_후보는_나온다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/stops/suggest")
                        .param("query", StubPlaceSearchClient.UNAVAILABLE_MARKER + "길")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    private String 관계자A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
