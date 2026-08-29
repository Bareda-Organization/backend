package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.9 {@code /staff/routes} — RTE-01 · RTE-09 · A-08 (Ruling 180 이 {@code [조정 중]} 을 부분 해제한 경로).
 *
 * <p><b>이 클래스의 최우선 단언은 차량 × 요일 × 방향 조합이 실제로 막히는가</b>다. 막히지 않으면 같은
 * 차량의 같은 요일·방향 편성이 둘 서고, 확정 배치가 어느 편성을 읽어야 하는지 정할 수단이 사라진다.
 *
 * <p><b>동시 요청 축은 여기서 검사되지 않는다</b> — 이 클래스에 {@code @Transactional} 이 붙어 있어
 * 두 요청이 서로의 미커밋 INSERT 를 못 보는 상황 자체가 만들어지지 않는다. 그 축은 비트랜잭션
 * 시험인 {@code RouteRegistrationConcurrencyTest} 가 맡는다.
 *
 * <p>삭제가 <b>행을 지우는 것</b>이라는 점도 함께 본다 — {@code route} 에 {@code deleted_at} 이
 * 부재한 것이 ERD §3.3 · §7.1 의 설계이고, 정차 순서는 FK CASCADE 로 함께 사라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRouteControllerTest {

    /** 시드의 학원 A 관계자({@code staffA})와 그 학원. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 격리 검증에서 남의 학원 편성을 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /**
     * 시드 학원 A 의 2호차 — 고정 노선이 하나도 매달려 있지 않아 어느 요일·방향이든 비어 있다.
     *
     * <p>1호차를 쓰지 않는 이유는 시드가 <b>오늘 요일</b>의 등원 편성을 이미 걸어 두었기 때문이다
     * ({@code V2__seed_data.sql} 의 {@code route} 1행) — 요일이 실행일에 따라 바뀌므로 1호차로는
     * "비어 있는 조합" 을 고정해 적을 수 없다.
     */
    private static final long BUS_A_ID = 2L;

    /** 시드 학원 B 의 1호차 — 학원 A 토큰으로 지목하면 {@code 404 BUS_NOT_FOUND} 여야 한다. */
    private static final long BUS_B_ID = 3L;

    /** 시드 학원 A 의 승하차지 4곳({@code stop} 1~4). */
    private static final List<Long> STOPS_OF_A = List.of(1L, 2L, 3L, 4L);

    /** 시드 학원 B 의 승하차지 — 학원 A 토큰으로 편성에 넣으려 하면 거부돼야 한다. */
    private static final long STOP_OF_B = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 변경 감지·삭제가 만든 SQL 은 커밋 시점에야 나간다 — 롤백되는 테스트에서 {@link JdbcTemplate} 로
     * 행을 읽어 보려면 그 전에 {@code flush()} 로 밀어내야 한다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // ── RTE-01 등록 ───────────────────────────────────────────────────────

    /**
     * 소속 학원은 <b>토큰이 정한다</b>(§1.5) — 요청 본문에 학원을 담을 자리가 부재한 것이 그 규칙을
     * 지키는 방식이라, 저장된 {@code academy_id} 를 DB 에서 직접 읽어 대조한다.
     */
    @Test
    void 노선을_편성하면_학원_소속으로_저장되고_정차_순서가_보낸_차례대로_남는다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "mon", "to_academy", STOPS_OF_A);

        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM route WHERE id = ?", Long.class,
                routeId))
                .as("응답이 아니라 저장된 행의 학원이 판정 대상이다 — 본문으로 학원을 받으면 여기서 갈린다")
                .isEqualTo(ACADEMY_A_ID);
        assertThat(jdbcTemplate.queryForList(
                "SELECT stop_id FROM route_stop WHERE route_id = ? ORDER BY seq", Long.class, routeId))
                .as("편성은 순서를 가진 목록이다 — 보낸 차례가 seq 1..N 으로 그대로 남아야 한다")
                .containsExactlyElementsOf(STOPS_OF_A);
    }

    /**
     * 유일성 조합 셋이 {@code 409 DUPLICATE_ROUTE} 로 막힌다
     * ({@code uk_route_bus_weekday_direction}).
     *
     * <p>세 값 중 <b>하나만 다른</b> 요청이 통과하는 것까지 함께 본다 — 그것이 없으면 조합이 아니라
     * 차량 하나로 막는 구현(같은 차량의 등원·하원을 함께 거부)이 이 단언을 통과한다.
     */
    @Test
    void 같은_차량_요일_방향_조합을_두_번_편성하면_거부된다() throws Exception {
        편성한다(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", STOPS_OF_A).andExpect(status().isCreated());

        편성한다(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", STOPS_OF_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ROUTE"));

        편성한다(관계자A_토큰(), BUS_A_ID, "tue", "from_academy", STOPS_OF_A)
                .andExpect(status().isCreated());
    }

    /** 다른 학원의 차량을 지목한 편성은 {@code 404 BUS_NOT_FOUND} 다 — 그 차량의 존재 여부를 드러내지 않는다. */
    @Test
    void 다른_학원의_차량으로는_노선을_편성할_수_없다() throws Exception {
        편성한다(관계자A_토큰(), BUS_B_ID, "wed", "to_academy", STOPS_OF_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
    }

    /**
     * 다른 학원의 승하차지를 편성에 넣으려 하면 거부된다 — 통과하면 한 노선의 정차지가 두 학원에
     * 걸쳐 서고, 그 노선의 학생 명단이 어느 학원 것인지가 두 테이블에서 갈린다.
     *
     * <p>대상이 <b>실재</b>하는 것을 함께 확인한다 — 실재하지 않는 id 를 쓰면 격리가 아니라 부재를
     * 검사하는 것이 되어, 학원 조건을 통째로 지워도 이 단언이 통과한다.
     */
    @Test
    void 다른_학원의_승하차지는_편성에_넣을_수_없다() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM stop WHERE id = ?", Long.class,
                STOP_OF_B)).isEqualTo(ACADEMY_B_ID);

        편성한다(관계자A_토큰(), BUS_A_ID, "thu", "to_academy", List.of(1L, STOP_OF_B))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 같은 승하차지를 한 편성에 두 번 넣으면 거부된다 — 통과하면 버스가 같은 자리에 두 번 선다. */
    @Test
    void 같은_승하차지를_두_번_담은_편성은_거부된다() throws Exception {
        편성한다(관계자A_토큰(), BUS_A_ID, "fri", "to_academy", List.of(1L, 2L, 1L))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── RTE-01 조회 · 수정 · 삭제 ─────────────────────────────────────────

    /** 상세 조회는 정차 순서를 {@code seq} 차례로 싣는다 — 순서가 빠지면 편성이 목록이 아니라 집합이 된다. */
    @Test
    void 노선_상세는_정차_순서를_순번_차례로_돌려준다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "to_academy", List.of(3L, 1L, 4L));

        String body = 본문(mockMvc.perform(get("/api/v1/staff/routes/" + routeId)
                .header("Authorization", 관계자A_토큰())).andExpect(status().isOk()).andReturn());

        assertThat(JsonPath.<List<Integer>>read(body, "$.data.stops[*].stop_id"))
                .as("보낸 차례가 그대로 나와야 한다 — 정렬을 빼면 DB 가 돌려주는 임의 순서가 실린다")
                .containsExactly(3, 1, 4);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.stops[*].seq")).containsExactly(1, 2, 3);
    }

    /**
     * {@code active=false} 로 고치면 그 값이 저장된다 — 확정 배치가 읽는 조건이라 저장되지 않으면
     * 쉬는 편성이 계속 노선으로 나간다.
     */
    @Test
    void 노선을_비활성으로_고치면_active_가_false_로_저장된다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "to_academy", STOPS_OF_A);

        수정한다(관계자A_토큰(), routeId, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM route WHERE id = ?", Boolean.class,
                routeId)).isFalse();
    }

    /** 수정도 유일성 조합을 받는다 — 방향만 옮겨도 기존 편성과 겹치면 {@code 409} 다. */
    @Test
    void 수정으로_다른_노선과_같은_조합이_되면_거부된다() throws Exception {
        편성한다(관계자A_토큰(), BUS_A_ID, "mon", "from_academy", STOPS_OF_A).andExpect(status().isCreated());
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "mon", "to_academy", STOPS_OF_A);

        수정한다(관계자A_토큰(), routeId, "{\"direction\":\"from_academy\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ROUTE"));
    }

    /** 자기 자신을 그대로 다시 보내는 수정은 통과한다 — 걸러내지 않으면 이름만 고치려는 요청이 막힌다. */
    @Test
    void 같은_조합을_그대로_다시_보내는_수정은_통과한다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "tue", "from_academy", STOPS_OF_A);

        수정한다(관계자A_토큰(), routeId, "{\"direction\":\"from_academy\",\"name\":\"바뀐 편성 이름\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("바뀐 편성 이름"));
    }

    /** {@code stop_ids} 를 보내면 정차 순서가 통째로 갈린다 — 보내지 않으면 손대지 않는다. */
    @Test
    void 수정이_정차_순서를_통째로_바꾸고_보내지_않으면_손대지_않는다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "wed", "to_academy", List.of(1L, 2L));

        수정한다(관계자A_토큰(), routeId, "{\"stop_ids\":[4,3,1]}").andExpect(status().isOk());
        entityManager.flush();
        assertThat(정차_순서(routeId)).containsExactly(4L, 3L, 1L);

        수정한다(관계자A_토큰(), routeId, "{\"name\":\"이름만 고침\"}").andExpect(status().isOk());
        entityManager.flush();
        assertThat(정차_순서(routeId))
                .as("stop_ids 가 없는 PATCH 가 정차 순서를 지우면 이름만 고치려던 요청이 편성을 날린다")
                .containsExactly(4L, 3L, 1L);
    }

    /**
     * 학원 격리 — 남의 학원 편성을 {@code {id}} 로 지목하면 {@code 404 ROUTE_NOT_FOUND} 다
     * (Ruling 163: {@code {id}} 지목은 404).
     *
     * <p>대상이 <b>실재</b>하는 것을 함께 확인한다 — 실재하지 않는 id 를 쓰면 격리가 아니라 부재를
     * 검사하는 것이 되어, 학원 조건을 통째로 지워도 이 단언이 통과한다.
     */
    @Test
    void 다른_학원의_노선은_조회_수정_삭제_최적화_어느_쪽으로도_닿지_않는다() throws Exception {
        long routeId = 편성된_노선_id(관계자B_토큰(), BUS_B_ID, "mon", "to_academy", List.of(STOP_OF_B));
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM route WHERE id = ?", Long.class,
                routeId)).isEqualTo(ACADEMY_B_ID);

        상세를_읽는다(관계자A_토큰(), routeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        수정한다(관계자A_토큰(), routeId, "{\"active\":false}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        최적화한다(관계자A_토큰(), routeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        삭제한다(관계자A_토큰(), routeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    /**
     * 삭제는 <b>행을 지우고</b> 정차 순서도 함께 사라진다({@code ERD} FK CASCADE) —
     * {@code route} 에 {@code deleted_at} 이 부재한 것이 §7.1 의 설계다.
     *
     * <p>정차 순서 잔존을 함께 보지 않으면 노선만 지우고 자식 행을 남기는 구현이 통과한다 — 그
     * 상태에서 같은 조합을 다시 편성하면 지운 편성의 정차지가 남아 있는 채로 보이지 않는다.
     */
    @Test
    void 노선을_삭제하면_행과_정차_순서가_함께_사라진다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "thu", "from_academy", STOPS_OF_A);
        assertThat(정차_순서(routeId)).as("정차지가 없으면 아래 부재 단언은 아무것도 검사하지 않는다").isNotEmpty();

        삭제한다(관계자A_토큰(), routeId).andExpect(status().isOk());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM route WHERE id = ?", Integer.class,
                routeId)).as("route 에는 deleted_at 이 부재하다 — 삭제는 행을 지우는 것이다").isZero();
        assertThat(정차_순서(routeId)).isEmpty();
    }

    // ── RTE-01 목록 ───────────────────────────────────────────────────────

    /** 목록은 소속 학원 것만 담는다 — 목록 조회는 조건이 빠져도 동작해 눈에 띄지 않는다. */
    @Test
    void 노선_목록은_소속_학원_것만_돌려준다() throws Exception {
        편성한다(관계자B_토큰(), BUS_B_ID, "tue", "to_academy", List.of(STOP_OF_B)).andExpect(status().isCreated());
        String bodyOfA = 본문(mockMvc.perform(get("/api/v1/staff/routes?size=100")
                .header("Authorization", 관계자A_토큰())).andExpect(status().isOk()).andReturn());

        assertThat((int) JsonPath.read(bodyOfA, "$.data.items.length()"))
                .as("목록이 비면 아래 부재 단언은 아무것도 검사하지 않는다").isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM route WHERE academy_id <> ?",
                Integer.class, ACADEMY_A_ID))
                .as("타 학원 편성이 실재해야 격리가 무언가를 격리한 것이 된다").isPositive();
        assertThat(JsonPath.<List<Integer>>read(bodyOfA, "$.data.items[*].id"))
                .allSatisfy(id -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT academy_id FROM route WHERE id = ?", Long.class, id.longValue()))
                        .isEqualTo(ACADEMY_A_ID));
    }

    // ── RTE-09 최적화 ─────────────────────────────────────────────────────

    /**
     * 최적화는 {@code route_stop} 의 순서를 실제로 갈아 끼운다(RTE-09).
     *
     * <p>시드 승하차지 4곳은 남서에서 북동으로 일렬로 놓여 있어({@code stop} 1~4 의 위경도가 함께
     * 증가), 남서쪽 기준점에서 출발하면 최적 순서가 <b>1·2·3·4</b> 하나로 정해진다. 그래서
     * 뒤집힌 차례로 편성해 두고 최적화를 부르면 산출이 유일하게 결정된다.
     *
     * <p>순서가 <b>바뀐 것</b>과 {@code seq} 가 <b>1부터 빈틈 없이</b> 이어지는 것을 함께 본다 —
     * 뒤는 재배열 도중 {@code uk_route_stop_route_seq} 를 피하려다 순번을 건너뛰는 구현을 막는다.
     */
    @Test
    void 최적화하면_정차_순서가_엔진_산출로_갈린다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "fri", "from_academy", List.of(4L, 3L, 2L, 1L));

        String body = 본문(최적화한다(관계자A_토큰(), routeId).andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.stops[*].stop_id"))
                .as("응답이 재배열 결과를 그대로 실어야 관리자가 무엇이 달라졌는지 본다")
                .containsExactly(1, 2, 3, 4);

        entityManager.flush();
        assertThat(정차_순서(routeId))
                .as("응답만 바뀌고 저장이 그대로면 다음 조회가 옛 순서를 돌려준다")
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbcTemplate.queryForList("SELECT seq FROM route_stop WHERE route_id = ? ORDER BY seq",
                Integer.class, routeId))
                .as("순번은 1부터 빈틈 없이 이어져야 한다 — 재배열이 순번을 건너뛰면 기사 화면에서 한 자리가 사라진다")
                .containsExactly(1, 2, 3, 4);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private List<Long> 정차_순서(long routeId) {
        return jdbcTemplate.queryForList("SELECT stop_id FROM route_stop WHERE route_id = ? ORDER BY seq",
                Long.class, routeId);
    }

    private ResultActions 편성한다(String token, long busId, String weekday, String direction,
            List<Long> stopIds) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/routes")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"weekday":"%s","direction":"%s","name":"본선","stop_ids":%s}"""
                        .formatted(busId, weekday, direction, stopIds)));
    }

    private long 편성된_노선_id(String token, long busId, String weekday, String direction, List<Long> stopIds)
            throws Exception {
        MvcResult result = 편성한다(token, busId, weekday, direction, stopIds)
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private ResultActions 상세를_읽는다(String token, long routeId) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/routes/" + routeId).header("Authorization", token));
    }

    private ResultActions 수정한다(String token, long routeId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/routes/" + routeId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 삭제한다(String token, long routeId) throws Exception {
        return mockMvc.perform(delete("/api/v1/staff/routes/" + routeId).header("Authorization", token));
    }

    /**
     * 기준점은 시드 승하차지 4곳의 남서쪽 바깥이다 — 편성에는 좌표 기준점을 담을 자리가 부재해
     * ({@code ERD route}) 요청이 준다.
     */
    private ResultActions 최적화한다(String token, long routeId) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/routes/" + routeId + "/optimize")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"origin":{"lat":37.565000,"lng":126.977000},
                         "destination":{"lat":37.570500,"lng":126.982000}}"""));
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
