package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.9 "승하차지 관리 — 목록 · 수정" — {@code GET /staff/stops} · {@code PATCH /staff/stops/{id}}(RTE-01 · A-08, Ruling 849).
 *
 * <p>목록이 지켜야 할 것은 셋이다 — <b>학원 격리</b>(남의 승하차지가 보이면 안 된다), <b>학생 수의 단위</b>(한 학생이 요일·방향마다 행을
 * 가져도 1명), <b>쪽당 질의 수 고정</b>(승하차지마다 편성·학생 수를 조회하면 목록 한 번이 질의 수십 건이 된다). 수정이 지켜야 할 것은
 * <b>운행 중 좌표 잠금</b>이다 — {@code PUT /staff/routes/{id}/stops} 가 쓰는 판정과 같은 것이어야 해서 같은 시험 장면(시드 운행 중 회차 R3)을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffStopManagementControllerTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /** 시드 학원 A 의 2호차 — 고정 노선이 하나도 없어 어느 요일·방향이든 비어 있다. */
    private static final long BUS_A_ID = 2L;

    /** 시드 학원 B 의 승하차지 — 학원 A 토큰으로 지목하면 {@code 404 STOP_NOT_FOUND} 여야 한다. */
    private static final long STOP_OF_B = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** 변경 감지가 만든 UPDATE 는 커밋 시점에 나간다 — 롤백되는 시험에서 JDBC 로 읽기 전에 밀어내야 한다. */
    @PersistenceContext
    private EntityManager entityManager;

    // ── GET /staff/stops ─────────────────────────────────────────────────

    /** {@code q} 는 이름과 주소 양쪽에 걸리고 대소문자를 가리지 않는다. {@code %} 는 와일드카드가 아니라 글자다. */
    @Test
    void q_는_이름과_주소_모두에_걸리고_대소문자를_가리지_않는다() throws Exception {
        long 이름쪽 = 승하차지를_심는다(ACADEMY_A_ID, "QzAlpha 정문", "서울시 가나로 1");
        long 주소쪽 = 승하차지를_심는다(ACADEMY_A_ID, "보조정류장", "서울시 qzbeta로 2");

        assertThat(목록_ids("qzalpha")).containsExactly(이름쪽);
        assertThat(목록_ids("QZBETA")).containsExactly(주소쪽);
        assertThat(목록_ids("가나로")).containsExactly(이름쪽);
        assertThat(목록_ids("qz")).containsExactlyInAnyOrder(이름쪽, 주소쪽);
        assertThat(목록_ids("%")).as("% 는 글자일 뿐 전부를 뜻하지 않는다").isEmpty();
    }

    /** 다른 학원 승하차지는 목록에도 검색에도 나타나지 않는다. */
    @Test
    void 다른_학원_승하차지는_목록에_없다() throws Exception {
        assertThat(목록_ids("")).contains(1L, 2L, 3L, 4L).doesNotContain(STOP_OF_B);
        assertThat(목록_ids("B학원")).isEmpty();
        assertThat(목록(관계자B_토큰(), "")).containsExactly(STOP_OF_B);
    }

    @Test
    void 목록은_이름_오름차순이고_동명은_stop_id_순이다() throws Exception {
        long 둘째_A = 승하차지를_심는다(ACADEMY_A_ID, "정렬시험B", "주소 1");
        long 첫째_A = 승하차지를_심는다(ACADEMY_A_ID, "정렬시험A", "주소 2");
        long 셋째_A = 승하차지를_심는다(ACADEMY_A_ID, "정렬시험A", "주소 3");

        assertThat(목록_ids("정렬시험")).containsExactly(첫째_A, 셋째_A, 둘째_A);
    }

    /** 비활성 편성도 싣고, 편성이 없으면 빈 배열이다. */
    @Test
    void routes_는_비활성_편성도_싣고_편성이_없으면_빈_배열이다() throws Exception {
        long 편성있음 = 승하차지를_심는다(ACADEMY_A_ID, "편성시험 있음", "주소");
        long 편성없음 = 승하차지를_심는다(ACADEMY_A_ID, "편성시험 없음", "주소");
        long 활성 = 편성을_심는다(BUS_A_ID, "mon", "to_academy", true, 편성있음);
        long 비활성 = 편성을_심는다(BUS_A_ID, "tue", "from_academy", false, 편성있음);

        String body = 목록_본문(관계자A_토큰(), "편성시험");
        List<Map<String, Object>> 편성들 = JsonPath.read(body, "$.data.items[?(@.stop_id=='" + 편성있음 + "')].routes[*]");
        assertThat(편성들).hasSize(2);
        assertThat(편성들).anySatisfy(route -> assertThat(route)
                .containsEntry("route_id", String.valueOf(활성)).containsEntry("bus_no", "2호차")
                .containsEntry("weekday", "mon").containsEntry("direction", "to_academy").containsEntry("active", true));
        assertThat(편성들).anySatisfy(route -> assertThat(route)
                .containsEntry("route_id", String.valueOf(비활성)).containsEntry("weekday", "tue")
                .containsEntry("direction", "from_academy").containsEntry("active", false));
        List<Object> 빈 = JsonPath.read(body, "$.data.items[?(@.stop_id=='" + 편성없음 + "')].routes");
        assertThat(빈).hasSize(1);
        assertThat((List<?>) 빈.get(0)).isEmpty();
    }

    /**
     * 시드 승하차지 1 은 학생 1 의 요일 × 방향 14행이 가리킨다 — 행을 세면 14, 학생을 세면 1. 승하차지 3 은 학생 2명(3 · 4).
     */
    @Test
    void student_count_는_한_학생이_여러_요일_방향으로_매칭돼도_1명이다() throws Exception {
        String body = 목록_본문(관계자A_토큰(), "");

        assertThat(학생_수(body, 1L)).isEqualTo(1);
        assertThat(학생_수(body, 2L)).isEqualTo(1);
        assertThat(학생_수(body, 3L)).isEqualTo(2);
        assertThat(학생_수(body, 4L)).isEqualTo(1);
    }

    @Test
    void student_count_에서_퇴원생은_빠진다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "퇴원시험 정류장", "주소");
        학생과_주소를_심는다("퇴원시험재원", false, "mon", stopId);
        학생과_주소를_심는다("퇴원시험퇴원", true, "mon", stopId);

        assertThat(학생_수(목록_본문(관계자A_토큰(), "퇴원시험"), stopId)).isEqualTo(1);
    }

    /** 승하차지가 2곳이든 20곳이든 쪽 하나를 읽는 SQL 수는 같다 — 승하차지마다 편성·학생 수를 읽으면 20곳에서 늘어난다. */
    @Test
    void 쪽당_질의_수는_승하차지_수와_무관하게_고정이다() throws Exception {
        String[] 요일 = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        long[] 편성 = new long[14];
        for (int i = 0; i < 20; i++) {
            long stopId = 승하차지를_심는다(ACADEMY_A_ID, "질의시험 " + i, "주소 " + i);
            if (i < 14) {
                편성[i] = 편성을_심는다(BUS_A_ID, 요일[i / 2], i % 2 == 0 ? "to_academy" : "from_academy", true, stopId);
            } else {
                jdbcTemplate.update("INSERT INTO route_stop (route_id, stop_id, seq) VALUES (?, ?, 2)", 편성[i - 14], stopId);
            }
            학생과_주소를_심는다("질의시험학생" + i, false, "mon", stopId);
        }

        long 둘 = 쿼리_수("질의시험", 2);
        long 스물 = 쿼리_수("질의시험", 20);

        assertThat(스물).as("size=20 이 size=2 와 같은 수의 SQL 로 읽혀야 한다").isEqualTo(둘);
        assertThat(JsonPath.<List<Object>>read(목록_본문(관계자A_토큰(), "질의시험", 20), "$.data.items")).hasSize(20);
    }

    // ── PATCH /staff/stops/{id} ──────────────────────────────────────────

    @Test
    void 이름만_고치면_200_이고_좌표와_주소는_그대로다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "고치기 전", "원래 주소");
        편성을_심는다(BUS_A_ID, "sat", "to_academy", true, stopId);

        수정한다(관계자A_토큰(), stopId, "{\"name\":\"고친 뒤\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stop_id").value(String.valueOf(stopId)))
                .andExpect(jsonPath("$.data.name").value("고친 뒤"))
                .andExpect(jsonPath("$.data.address").value("원래 주소"))
                .andExpect(jsonPath("$.data.routes.length()").value(1))
                .andExpect(jsonPath("$.data.student_count").value(0));
        assertThat(승하차지_행(stopId)).containsEntry("name", "고친 뒤").containsEntry("address", "원래 주소")
                .containsEntry("lat", new BigDecimal("37.500000")).containsEntry("lng", new BigDecimal("127.000000"));
    }

    /** 노선 편성 화면의 상세({@code GET /staff/routes/{id}})가 같은 승하차지 행을 읽으므로 그쪽에서도 새 좌표가 보인다. */
    @Test
    void 좌표를_고치면_그_승하차지를_담은_노선_상세의_좌표가_바뀐다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "좌표시험", "주소");
        long routeId = 편성을_심는다(BUS_A_ID, "sat", "to_academy", true, stopId);

        수정한다(관계자A_토큰(), stopId, "{\"lat\":37.512345,\"lng\":126.987654}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lat").value(37.512345))
                .andExpect(jsonPath("$.data.lng").value(126.987654));

        mockMvc.perform(get("/api/v1/staff/routes/" + routeId).header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops[0].lat").value(37.512345))
                .andExpect(jsonPath("$.data.stops[0].lng").value(126.987654));
    }

    @Test
    void 좌표를_한쪽만_보내면_422_이고_다른_필드도_바뀌지_않는다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "한쪽시험", "주소");

        for (String 본문 : List.of("{\"name\":\"바뀌면 안 됨\",\"lat\":37.6}", "{\"name\":\"바뀌면 안 됨\",\"lng\":127.1}")) {
            수정한다(관계자A_토큰(), stopId, 본문)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(승하차지_행(stopId)).containsEntry("name", "한쪽시험")
                .containsEntry("lat", new BigDecimal("37.500000")).containsEntry("lng", new BigDecimal("127.000000"));
    }

    /**
     * 운행 중 회차가 서는 승하차지의 좌표는 아무것도 바꾸지 않은 채 {@code 403} 이다 — 같은 요청의 이름 변경도 함께 막힌다(통째로 거절).
     * 이름만 고치는 요청은 판정에 쓰이지 않아 허용된다(BR-052).
     */
    @Test
    void 운행_중_회차가_서는_승하차지의_좌표는_바꿀_수_없고_이름만은_바꿀_수_있다() throws Exception {
        // 시드 R3 의 운행일은 시드를 깐 날이라 테스트 DB 가 이틀 넘게 묵으면 "어제 이후" 범위(Ruling 701)를 벗어난다 — 오늘로 고정한다
        jdbcTemplate.update("UPDATE run SET service_date = (now() AT TIME ZONE 'Asia/Seoul')::date WHERE id = 3");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = 3", String.class)).isEqualTo("moving");
        Map<String, Object> 전 = 승하차지_행(1L);

        수정한다(관계자A_토큰(), 1L, "{\"name\":\"좌표와 함께\",\"lat\":37.599999,\"lng\":126.999999}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
        assertThat(승하차지_행(1L)).as("거절된 요청은 이름도 바꾸지 않는다").isEqualTo(전);

        수정한다(관계자A_토큰(), 1L, "{\"name\":\"이름만 바꿈\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("이름만 바꿈"));
    }

    @Test
    void 다른_학원_승하차지와_없는_승하차지는_똑같이_404_다() throws Exception {
        Map<String, Object> 전 = 승하차지_행(STOP_OF_B);

        수정한다(관계자A_토큰(), STOP_OF_B, "{\"name\":\"남의 것\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STOP_NOT_FOUND"));
        수정한다(관계자A_토큰(), 987654321L, "{\"name\":\"없는 것\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STOP_NOT_FOUND"));
        assertThat(승하차지_행(STOP_OF_B)).isEqualTo(전);
    }

    @Test
    void 길이_범위_공백_위반은_422_이고_아무것도_바뀌지_않는다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "검증시험", "주소");
        Map<String, Object> 전 = 승하차지_행(stopId);

        for (String 본문 : List.of(
                "{\"name\":\"" + "가".repeat(101) + "\"}",
                "{\"address\":\"" + "가".repeat(256) + "\"}",
                "{\"name\":\"   \"}",
                "{\"lat\":91.0,\"lng\":127.0}",
                "{\"lat\":37.5,\"lng\":181.0}")) {
            수정한다(관계자A_토큰(), stopId, 본문)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(승하차지_행(stopId)).isEqualTo(전);
    }

    /** 좌표를 옮기면 노선 저장({@code PUT /staff/routes/{id}/stops}, Ruling 703)처럼 그 학원 회차의 확정 실패 이력이 지워진다. */
    @Test
    void 좌표를_옮기면_그_학원_회차의_확정_실패_이력이_지워지고_다른_학원은_그대로다() throws Exception {
        long stopId = 승하차지를_심는다(ACADEMY_A_ID, "실패이력시험", "주소");
        jdbcTemplate.update("UPDATE run SET consecutive_failures = 4, confirm_retry_at = now() + interval '10 minutes' "
                + "WHERE id = 1");
        jdbcTemplate.update("UPDATE run SET status = 'idle', confirmed_at = NULL, consecutive_failures = 4, "
                + "confirm_retry_at = now() + interval '10 minutes' WHERE id = 5");

        수정한다(관계자A_토큰(), stopId, "{\"name\":\"이름만\"}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT consecutive_failures FROM run WHERE id = 1", Integer.class))
                .as("이름만 고친 요청은 노선 계산에 영향이 없어 이력을 지우지 않는다").isEqualTo(4);

        수정한다(관계자A_토큰(), stopId, "{\"lat\":37.6,\"lng\":127.1}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForMap("SELECT consecutive_failures, confirm_retry_at FROM run WHERE id = 1"))
                .containsEntry("consecutive_failures", 0).containsEntry("confirm_retry_at", null);
        assertThat(jdbcTemplate.queryForObject("SELECT consecutive_failures FROM run WHERE id = 5", Integer.class))
                .as("다른 학원 회차는 그대로").isEqualTo(4);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private long 승하차지를_심는다(long academyId, String name, String address) {
        return jdbcTemplate.queryForObject("INSERT INTO stop (academy_id, name, address, lat, lng, created_at, updated_at) "
                + "VALUES (?, ?, ?, 37.500000, 127.000000, now(), now()) RETURNING id", Long.class, academyId, name, address);
    }

    private long 편성을_심는다(long busId, String weekday, String direction, boolean active, long stopId) {
        long routeId = jdbcTemplate.queryForObject("INSERT INTO route (academy_id, bus_id, weekday, direction, name, active, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, '시험 편성', ?, now(), now()) RETURNING id", Long.class,
                ACADEMY_A_ID, busId, weekday, direction, active);
        jdbcTemplate.update("INSERT INTO route_stop (route_id, stop_id, seq) VALUES (?, ?, 1)", routeId, stopId);
        return routeId;
    }

    private void 학생과_주소를_심는다(String name, boolean 퇴원, String weekday, long stopId) {
        long studentId = jdbcTemplate.queryForObject(
                "INSERT INTO student (academy_id, name, deleted_at) VALUES (?, ?, ?) RETURNING id", Long.class,
                ACADEMY_A_ID, name, 퇴원 ? OffsetDateTime.now() : null);
        jdbcTemplate.update("INSERT INTO weekly_address (student_id, weekday, direction, address, verified, stop_id) "
                + "VALUES (?, ?, 'to_academy', '테스트 주소', true, ?)", studentId, weekday, stopId);
    }

    private Map<String, Object> 승하차지_행(long stopId) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT name, address, lat, lng FROM stop WHERE id = ?", stopId);
    }

    private long 학생_수(String body, long stopId) {
        List<Integer> counts = JsonPath.read(body, "$.data.items[?(@.stop_id=='" + stopId + "')].student_count");
        assertThat(counts).as("승하차지 " + stopId + " 이(가) 목록에 있어야 한다").hasSize(1);
        return counts.get(0);
    }

    private List<Long> 목록_ids(String q) throws Exception {
        return 목록(관계자A_토큰(), q);
    }

    private List<Long> 목록(String token, String q) throws Exception {
        List<String> ids = JsonPath.read(목록_본문(token, q), "$.data.items[*].stop_id");
        return ids.stream().map(Long::valueOf).toList();
    }

    private String 목록_본문(String token, String q) throws Exception {
        return 목록_본문(token, q, 100);
    }

    private String 목록_본문(String token, String q, int size) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/stops").param("q", q).param("size", String.valueOf(size))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long 쿼리_수(String q, int size) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        목록_본문(관계자A_토큰(), q, size);
        return statistics.getPrepareStatementCount();
    }

    private ResultActions 수정한다(String token, long stopId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/stops/" + stopId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String 관계자A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    private String 관계자B_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_B_ACCOUNT_ID, ACADEMY_B_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
