package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 같은 승하차지를 한 편성에 두 번 넣으면 거부된다 — 통과하면 버스가 같은 자리에 두 번 선다. */
    @Test
    void 같은_승하차지를_두_번_담은_편성은_거부된다() throws Exception {
        편성한다(관계자A_토큰(), BUS_A_ID, "fri", "to_academy", List.of(1L, 2L, 1L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── RTE-01 조회 · 수정 · 삭제 ─────────────────────────────────────────

    /** 상세 조회는 정차 순서를 {@code seq} 차례로 싣는다 — 순서가 빠지면 편성이 목록이 아니라 집합이 된다. */
    @Test
    void 노선_상세는_정차_순서를_순번_차례로_돌려준다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "to_academy", List.of(3L, 1L, 4L));

        String body = 본문(mockMvc.perform(get("/api/v1/staff/routes/" + routeId)
                .header("Authorization", 관계자A_토큰())).andExpect(status().isOk()).andReturn());

        assertThat(JsonPath.<List<String>>read(body, "$.data.stops[*].stop_id"))
                .as("보낸 차례가 그대로 나와야 한다 — 정렬을 빼면 DB 가 돌려주는 임의 순서가 실린다")
                .containsExactly("3", "1", "4");
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

        삭제한다(관계자A_토큰(), routeId).andExpect(status().isNoContent());

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
        String bodyOfA = 목록_본문(관계자A_토큰(), "size=100");

        assertThat((int) JsonPath.read(bodyOfA, "$.data.items.length()"))
                .as("목록이 비면 아래 부재 단언은 아무것도 검사하지 않는다").isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM route WHERE academy_id <> ?",
                Integer.class, ACADEMY_A_ID))
                .as("타 학원 편성이 실재해야 격리가 무언가를 격리한 것이 된다").isPositive();
        assertThat(JsonPath.<List<String>>read(bodyOfA, "$.data.items[*].id"))
                .allSatisfy(id -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT academy_id FROM route WHERE id = ?", Long.class, Long.parseLong(id)))
                        .isEqualTo(ACADEMY_A_ID));
    }

    /**
     * 목록 항목이 정차지 수({@code stop_count})를 싣는다(R46 FUBE · B1 #10) — 정차지가 없는 빈 편성과 채워진 편성을
     * 목록만 보고 가르는 데 쓴다. 3곳과 0곳을 함께 만들어 "모두 같은 값" 을 내는 구현을 막는다.
     */
    @Test
    void 노선_목록은_편성마다_정차지_수를_싣는다() throws Exception {
        long 채운편성 = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", List.of(1L, 2L, 3L));
        long 빈편성 = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "wed", "to_academy", List.of());

        String body = 목록_본문(관계자A_토큰(), "size=100");

        assertThat(JsonPath.<List<Integer>>read(body, "$.data.items[?(@.id == " + 채운편성 + ")].stop_count"))
                .as("정차지 3곳으로 편성한 노선").containsExactly(3);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.items[?(@.id == " + 빈편성 + ")].stop_count"))
                .as("정차지 없이 시작한 편성 — 0 이 실려야 빈 편성을 가를 수 있다").containsExactly(0);
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
        assertThat(JsonPath.<List<String>>read(body, "$.data.stops[*].stop_id"))
                .as("응답이 재배열 결과를 그대로 실어야 관리자가 무엇이 달라졌는지 본다")
                .containsExactly("1", "2", "3", "4");

        entityManager.flush();
        assertThat(정차_순서(routeId))
                .as("응답만 바뀌고 저장이 그대로면 다음 조회가 옛 순서를 돌려준다")
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbcTemplate.queryForList("SELECT seq FROM route_stop WHERE route_id = ? ORDER BY seq",
                Integer.class, routeId))
                .as("순번은 1부터 빈틈 없이 이어져야 한다 — 재배열이 순번을 건너뛰면 기사 화면에서 한 자리가 사라진다")
                .containsExactly(1, 2, 3, 4);
    }


    /**
     * 페이징 봉투 4개 필드가 <b>실제 값</b>으로 나온다(API_SPEC §1.8) — {@code page}·{@code size}·
     * {@code total_count}·{@code has_next}.
     *
     * <p><b>{@code size} 를 크게 줘서 페이징을 우회하면 이 넷이 아무 단언의 대상이 되지 않는다</b>
     * (수정 라운드 1 Important 2 · Ruling 168). 그래서 여러 쪽에 걸치도록 편성해 두고 {@code size} 를
     * 작게 준다.
     *
     * <p>마지막 쪽에서 {@code has_next} 가 {@code false} 인 것을 함께 본다 — 첫 쪽만 보면 항상
     * {@code true} 를 실어 보내는 구현이 통과하고, 그러면 클라이언트가 빈 쪽을 한 번 더 요청한다.
     */
    @Test
    void 목록이_페이징_봉투_네_필드를_실제_값으로_돌려준다() throws Exception {
        // ⚠ 시드 편성 수를 상수로 박지 않는다 — 시드가 늘 때마다(V12 PREVIEW_STALE 재현용 1건,
        // V13 데모 선단 3건) 이 시험이 깨졌다. 여기서 보는 것은 편성 개수가 아니라 **페이징 봉투
        // 네 필드가 실제 값을 싣는가** 이므로, 총계·마지막 쪽을 실측 시드 수에서 유도한다.
        int 시드_편성수 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM route WHERE academy_id = ?", Integer.class, ACADEMY_A_ID);
        편성한다(관계자A_토큰(), BUS_A_ID, "mon", "to_academy", STOPS_OF_A).andExpect(status().isCreated());
        편성한다(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", List.of()).andExpect(status().isCreated());
        int 총계 = 시드_편성수 + 2;
        int 쪽크기 = 2;
        int 마지막쪽 = (총계 - 1) / 쪽크기;
        assertThat(마지막쪽)
                .as("여러 쪽에 걸쳐야 has_next 가 단언의 대상이 된다(Ruling 168)")
                .isPositive();

        String 첫쪽 = 목록_본문(관계자A_토큰(), "page=0&size=" + 쪽크기);
        assertThat((int) JsonPath.read(첫쪽, "$.data.items.length()")).isEqualTo(쪽크기);
        assertThat((int) JsonPath.read(첫쪽, "$.data.page")).isZero();
        assertThat((int) JsonPath.read(첫쪽, "$.data.size")).isEqualTo(쪽크기);
        assertThat((int) JsonPath.read(첫쪽, "$.data.total_count"))
                .as("총계는 쪽 크기가 아니라 학원 전체 편성 수다 — 시드 + 새로 만든 2")
                .isEqualTo(총계);
        assertThat((boolean) JsonPath.read(첫쪽, "$.data.has_next")).isTrue();

        String 끝쪽 = 목록_본문(관계자A_토큰(), "page=" + 마지막쪽 + "&size=" + 쪽크기);
        assertThat((int) JsonPath.read(끝쪽, "$.data.page")).isEqualTo(마지막쪽);
        assertThat((int) JsonPath.read(끝쪽, "$.data.total_count")).isEqualTo(총계);
        assertThat((boolean) JsonPath.read(끝쪽, "$.data.has_next"))
                .as("마지막 쪽인데 다음이 있다고 답하면 클라이언트가 빈 쪽을 한 번 더 요청한다")
                .isFalse();
        assertThat(JsonPath.<List<String>>read(첫쪽, "$.data.items[*].id"))
                .as("두 쪽이 같은 항목을 담으면 페이지 위치가 반영되지 않은 것이다")
                .doesNotContainAnyElementsOf(JsonPath.read(끝쪽, "$.data.items[*].id"));
    }

    /**
     * {@code sort} 는 허용 목록({@code RouteQueryService.SORTABLE_FIELDS})에 있는 필드만 받고, 그
     * 차례로 돌려준다(§1.8 {@code {필드}:{asc|desc}}).
     *
     * <p>거부 축을 함께 보는 것이 요점이다 — 목록에 없는 이름을 그대로 정렬 속성으로 넘기면
     * Spring Data 가 {@code PropertyReferenceException} 을 던져 {@code 422} 여야 할 입력이
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나른다.
     *
     * <p>내가 만든 셋만 골라 차례를 본다 — 시드 편성의 이름이 한글이라 전체 목록의 차례는 DB 대조
     * 순서(collation)에 달려 있고, 그것은 이 코드가 정하는 것이 아니다.
     */
    @Test
    void 목록_정렬은_허용_필드만_받고_그_차례로_돌려준다() throws Exception {
        이름을_주어_편성한다("SORT-A", BUS_A_ID, "wed", "to_academy");
        이름을_주어_편성한다("SORT-C", BUS_A_ID, "wed", "from_academy");
        이름을_주어_편성한다("SORT-B", BUS_A_ID, "thu", "to_academy");

        List<String> 내림차순 = JsonPath.<List<String>>read(
                        목록_본문(관계자A_토큰(), "size=100&sort=name:desc"), "$.data.items[*].name")
                .stream().filter(name -> name != null && name.startsWith("SORT-")).toList();

        assertThat(내림차순)
                .as("sort 를 무시하면 기본 정렬(차량·요일·방향)이 나와 A·C·B 차례가 된다")
                .containsExactly("SORT-C", "SORT-B", "SORT-A");

        mockMvc.perform(get("/api/v1/staff/routes?sort=academy_id:asc")
                        .header("Authorization", 관계자A_토큰()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 삭제한 조합은 <b>같은 조합으로 다시 편성된다</b> — 삭제가 행을 지우는 것이라 유일성 자리가
     * 비기 때문이다(수정 라운드 1 Minor 1).
     *
     * <p>지금은 {@code route} 에 {@code deleted_at} 이 부재해 구조적으로 성립하나, 이 저장소는 소프트
     * 삭제가 유일성 자리를 점유한 채 남는 형태를 <b>두 번</b> 밟았다(Ruling 139 · Phase 5 이월②).
     * 나중에 누가 {@code route} 에 소프트 삭제를 도입하면 그때 이 단언이 실패해 알린다.
     */
    @Test
    void 삭제한_조합은_같은_조합으로_다시_편성할_수_있다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "from_academy", STOPS_OF_A);

        삭제한다(관계자A_토큰(), routeId).andExpect(status().isNoContent());
        entityManager.flush();

        편성한다(관계자A_토큰(), BUS_A_ID, "sat", "from_academy", STOPS_OF_A)
                .andExpect(status().isCreated());
    }

    /**
     * 정차지가 없는 편성에 최적화를 불러도 오류가 아니다(수정 라운드 1 Minor 2).
     *
     * <p>차량·요일·방향 칸을 먼저 잡아 두고 승하차지를 나중에 채우는 조작이 실재하므로, 그 중간
     * 상태에 최적화가 닿는 것은 조작 실수이지 사고가 아니다. 거부하면 화면이 설명할 이유가 부재하다.
     */
    @Test
    void 정차지가_없는_편성에_최적화를_불러도_오류가_아니다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "from_academy", List.of());

        String body = 본문(최적화한다(관계자A_토큰(), routeId).andExpect(status().isOk()).andReturn());

        assertThat((int) JsonPath.read(body, "$.data.stops.length()")).isZero();
    }

    // ── RTE-01 도로 경로(§5.9 신설) ───────────────────────────────────────

    /**
     * 정차지 2곳 이상인 편성은 {@code road_path} 가 비어 있지 않다 — 시드 학원 A 는 좌표를
     * 가지고 있어({@code V2__seed_data.sql}) 정차지끼리 + 학원 기준점까지 이어져 스텁
     * ({@code StubMapRouteClient})이 최소 1개 이상의 구간 좌표를 돌려준다.
     */
    @Test
    void 정차지가_2곳_이상인_노선의_도로_경로는_비어_있지_않다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "mon", "to_academy", List.of(1L, 2L));

        String body = 본문(경로를_읽는다(관계자A_토큰(), routeId).andExpect(status().isOk()).andReturn());

        assertThat((int) JsonPath.read(body, "$.data.road_path.length()"))
                .as("정차지가 2곳이면 학원 기준점까지 더해 최소 2점(구간 1개)이 나와야 한다")
                .isPositive();
        assertThat(JsonPath.<List<String>>read(body, "$.data.stops[*].stop_id")).containsExactly("1", "2");
        assertThat((boolean) JsonPath.read(body, "$.data.fallback_used")).isFalse();
    }

    /** 다른 학원의 편성을 {@code {id}/path} 로 지목하면 {@code 404 ROUTE_NOT_FOUND} 다. */
    @Test
    void 다른_학원의_노선은_도로_경로도_읽을_수_없다() throws Exception {
        long routeId = 편성된_노선_id(관계자B_토큰(), BUS_B_ID, "wed", "to_academy", List.of(STOP_OF_B));

        경로를_읽는다(관계자A_토큰(), routeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    /**
     * 정차지 하나가 다른 학원 소속으로 남아(데이터 정합 어긋남) {@code stop} 조인에서 빠져도
     * {@code 500} 이 아니라 나머지 정차지로 응답한다 — {@code route_stop} 에 학원 컬럼이 없어
     * (ERD §6.1) 이런 어긋남을 DB 가 막지 못한다({@code RoutePathQueryService.stopResponsesOf}).
     *
     * <p>실제로 어긋난 행을 만들 수 없어(편성 API 가 학원 밖 정차지를 거부, §5.9) JDBC 로
     * {@code route_stop} 을 직접 꽂아 그 상태를 재현한다.
     */
    @Test
    void 좌표를_못_찾는_정차지가_섞여도_500이_아니라_나머지로_응답한다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "thu", "to_academy", List.of(1L));
        jdbcTemplate.update("INSERT INTO route_stop (route_id, stop_id, seq) VALUES (?, ?, 2)", routeId,
                STOP_OF_B);
        entityManager.flush();
        entityManager.clear();

        String body = 본문(경로를_읽는다(관계자A_토큰(), routeId).andExpect(status().isOk()).andReturn());

        assertThat(JsonPath.<List<String>>read(body, "$.data.stops[*].stop_id"))
                .as("존재하지 않는(=타 학원) 정차지는 목록에서 빠지고 500 대신 나머지만 남는다")
                .containsExactly("1");
    }

    private ResultActions 경로를_읽는다(String token, long routeId) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/routes/" + routeId + "/path").header("Authorization", token));
    }

    // ── R48 Ruling 818·819 — 목록 쪽 크기 500 · 상세 rider_count ──────────────────────

    /** size 상한은 500 이다(§5.9, §1.8 의 100 예외) — 500 은 받고 501 은 422 로 거부한다(절삭하지 않는다). */
    @Test
    void 목록_size_는_500까지_받고_501은_422_다() throws Exception {
        assertThat((int) JsonPath.read(목록_본문(관계자A_토큰(), "size=500"), "$.data.size")).isEqualTo(500);

        mockMvc.perform(get("/api/v1/staff/routes?size=501").header("Authorization", 관계자A_토큰()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * {@code stops[].rider_count} — 그 편성의 요일·방향 요일별 주소가 그 승하차지로 매칭된 <b>재원</b> 학생만 센다(§5.9, Ruling 819).
     * 다른 방향·다른 요일의 주소와 퇴원생은 세지 않는다 — 시드 학생이 섞이지 않게 심기 전후의 차이를 본다.
     */
    @Test
    void 상세_stops_의_rider_count_는_같은_요일_방향의_재원_학생만_센다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "to_academy", List.of(1L, 2L));
        assertThat(JsonPath.<List<Object>>read(본문(상세를_읽는다(관계자A_토큰(), routeId).andReturn()),
                "$.data.stops[*].rider_count")).hasSize(2).doesNotContainNull();
        List<Integer> 전 = JsonPath.read(본문(상세를_읽는다(관계자A_토큰(), routeId).andReturn()),
                "$.data.stops[*].rider_count");

        학생과_주소를_심는다("카운트재원", false, "sun", "to_academy", 1L);
        학생과_주소를_심는다("카운트다른방향", false, "sun", "from_academy", 1L);
        학생과_주소를_심는다("카운트다른요일", false, "mon", "to_academy", 1L);
        학생과_주소를_심는다("카운트퇴원", true, "sun", "to_academy", 1L);
        학생과_주소를_심는다("카운트둘째정차지", false, "sun", "to_academy", 2L);

        List<Integer> 후 = JsonPath.read(본문(상세를_읽는다(관계자A_토큰(), routeId).andReturn()),
                "$.data.stops[*].rider_count");
        assertThat(후.get(0) - 전.get(0)).as("첫 정차지는 재원 1명만 늘어난다 — 다른 방향·다른 요일·퇴원생은 센 것이다").isEqualTo(1);
        assertThat(후.get(1) - 전.get(1)).isEqualTo(1);
    }

    private void 학생과_주소를_심는다(String name, boolean 퇴원, String weekday, String direction, long stopId) {
        long studentId = jdbcTemplate.queryForObject(
                "INSERT INTO student (academy_id, name, deleted_at) VALUES (?, ?, ?) RETURNING id", Long.class,
                ACADEMY_A_ID, name, 퇴원 ? java.time.OffsetDateTime.now() : null);
        jdbcTemplate.update("INSERT INTO weekly_address (student_id, weekday, direction, address, verified, stop_id) "
                + "VALUES (?, ?, ?, '테스트 주소', true, ?)", studentId, weekday, direction, stopId);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private List<Long> 정차_순서(long routeId) {
        entityManager.flush();
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

    // ── 승하차지 한 번에 저장(2026-09-23 사용자 지시 — 고친 뒤 저장 버튼 한 번에 반영) ─────────

    /**
     * 추가·수정·삭제·순서가 <b>한 요청</b>으로 들어가 모두 반영된다. 빠진 승하차지는 노선에서만 빠지고
     * 승하차지 자체는 남는다 — 다른 노선과 학생 주소가 그 승하차지를 가리키고 있다.
     */
    @Test
    void 저장_한_번에_추가_수정_삭제_순서가_모두_반영된다() throws Exception {
        운행_중_회차를_끝낸다();
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "to_academy", STOPS_OF_A);
        Map<String, Object> 일번 = jdbcTemplate.queryForMap("SELECT name, lat, lng FROM stop WHERE id = 1");

        String body = 본문(승하차지를_저장한다(관계자A_토큰(), routeId, """
                {"stops":[
                  {"stop_id":3,"name":"옮긴 3번","lat":37.555555,"lng":126.955555},
                  {"stop_id":1,"name":"%s","lat":%s,"lng":%s},
                  {"name":"새 모퉁이","address":"서울시 새길 7","lat":37.512345,"lng":126.512345}
                ]}""".formatted(일번.get("name"), 일번.get("lat"), 일번.get("lng")))
                .andExpect(status().isOk()).andReturn());
        entityManager.flush();

        long 새_승하차지 = jdbcTemplate.queryForObject(
                "SELECT id FROM stop WHERE academy_id = ? AND name = '새 모퉁이'", Long.class, ACADEMY_A_ID);
        assertThat(정차_순서(routeId)).containsExactly(3L, 1L, 새_승하차지);
        assertThat(jdbcTemplate.queryForMap("SELECT name, lat FROM stop WHERE id = 3"))
                .containsEntry("name", "옮긴 3번")
                .containsEntry("lat", new BigDecimal("37.555555"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stop WHERE id IN (2, 4)", Integer.class))
                .as("노선에서 뺀 승하차지는 지워지지 않는다 — 학생 주소가 그 행을 가리킨다")
                .isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.data.stops[*].name"))
                .containsExactly("옮긴 3번", (String) 일번.get("name"), "새 모퉁이");
    }

    /**
     * 한 항목이라도 거부되면 <b>앞 항목의 수정도 남지 않는다.</b> 앞에서부터 하나씩 반영하다 뒤에서
     * 실패하면 "3번 이름은 바뀌었는데 순서는 그대로" 인 반쯤 저장된 노선이 남는다.
     */
    @Test
    void 잘못된_항목이_하나라도_있으면_아무것도_바뀌지_않는다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "to_academy", STOPS_OF_A);
        String 원래_이름 = jdbcTemplate.queryForObject("SELECT name FROM stop WHERE id = 3", String.class);

        승하차지를_저장한다(관계자A_토큰(), routeId, """
                {"stops":[
                  {"stop_id":3,"name":"바뀌면 안 됨","lat":37.555555,"lng":126.955555},
                  {"stop_id":%d,"name":"남의 학원","lat":37.5,"lng":127.0}
                ]}""".formatted(STOP_OF_B))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM stop WHERE id = 3", String.class))
                .isEqualTo(원래_이름);
        assertThat(정차_순서(routeId)).containsExactlyElementsOf(STOPS_OF_A);
    }

    /**
     * 운행 중 회차가 서는 승하차지의 <b>좌표</b>는 바꿀 수 없다 — 운행 시작과 동시에 노선이 잠긴다
     * (ARCHITECTURE §8.5, BR-052). 통과시키면 그 회차의 근접 알림·출발 판정이 운행 도중 새 좌표로 바뀐다.
     * 이름만 고치는 것은 판정에 쓰이지 않아 허용된다.
     */
    @Test
    void 운행_중_회차가_서는_승하차지의_좌표는_바꿀_수_없다() throws Exception {
        // 시드 R3 의 운행일은 시드를 깐 날이라 테스트 DB 가 이틀 넘게 묵으면 "어제 이후" 범위(Ruling 701)를 벗어난다 — 오늘로 고정한다
        jdbcTemplate.update("UPDATE run SET service_date = (now() AT TIME ZONE 'Asia/Seoul')::date WHERE id = 3");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = 3", String.class))
                .as("시드 R3 는 운행 중이고 현재 판본이 승하차지 1~4 에 선다")
                .isEqualTo("moving");
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "to_academy", List.of(1L));
        Map<String, Object> 일번 = jdbcTemplate.queryForMap("SELECT name, lat, lng FROM stop WHERE id = 1");

        승하차지를_저장한다(관계자A_토큰(), routeId, """
                {"stops":[{"stop_id":1,"name":"%s","lat":37.599999,"lng":%s}]}"""
                .formatted(일번.get("name"), 일번.get("lng")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
        승하차지를_저장한다(관계자A_토큰(), routeId, """
                {"stops":[{"stop_id":1,"name":"이름만 바꿈","lat":%s,"lng":%s}]}"""
                .formatted(일번.get("lat"), 일번.get("lng")))
                .andExpect(status().isOk());
    }

    /**
     * R46-KFIXBE K-2(Ruling 703) — 노선을 편성·수정·승하차지 저장하면 그 학원 회차의 확정 실패 이력이 지워져 고친 즉시 다음 틱에 다시 시도한다
     * (노선이 없어 영구 실패하던 회차). 다른 학원 회차는 건드리지 않는다.
     */
    @Test
    void 노선을_저장하면_그_학원_회차의_확정_실패_이력이_지워지고_다른_학원은_그대로다() throws Exception {
        운행_중_회차를_끝낸다();
        실패_이력을_심는다();
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "to_academy", STOPS_OF_A);
        실패_이력이_학원_A_것만_지워졌는지("편성");

        실패_이력을_심는다();
        수정한다(관계자A_토큰(), routeId, "{\"stop_ids\":[4,3,1]}").andExpect(status().isOk());
        실패_이력이_학원_A_것만_지워졌는지("수정");

        실패_이력을_심는다();
        Map<String, Object> 일번 = jdbcTemplate.queryForMap("SELECT name, lat, lng FROM stop WHERE id = 1");
        승하차지를_저장한다(관계자A_토큰(), routeId, """
                {"stops":[{"stop_id":1,"name":"%s","lat":%s,"lng":%s}]}"""
                .formatted(일번.get("name"), 일번.get("lat"), 일번.get("lng")))
                .andExpect(status().isOk());
        실패_이력이_학원_A_것만_지워졌는지("승하차지 저장");
    }

    /** 학원 A 의 시드 회차 1 과 학원 B 의 회차 5(idle 로 되돌린다)를 확정 실패 중으로 만든다. */
    private void 실패_이력을_심는다() {
        jdbcTemplate.update("UPDATE run SET consecutive_failures = 4, confirm_retry_at = now() + interval '10 minutes' "
                + "WHERE id = 1");
        jdbcTemplate.update("UPDATE run SET status = 'idle', confirmed_at = NULL, consecutive_failures = 4, "
                + "confirm_retry_at = now() + interval '10 minutes' WHERE id = 5");
    }

    private void 실패_이력이_학원_A_것만_지워졌는지(String 저장_종류) {
        assertThat(jdbcTemplate.queryForMap("SELECT consecutive_failures, confirm_retry_at FROM run WHERE id = 1"))
                .as(저장_종류 + " — 같은 학원 회차는 바로 다시 시도").containsEntry("consecutive_failures", 0)
                .containsEntry("confirm_retry_at", null);
        assertThat(jdbcTemplate.queryForObject("SELECT consecutive_failures FROM run WHERE id = 5", Integer.class))
                .as(저장_종류 + " — 다른 학원 회차는 그대로").isEqualTo(4);
    }

    /**
     * R46-KFIXBE K-1(Ruling 701) — 끝나지 않은 채 남은 옛 회차(운행일이 어제보다 이른 {@code moving})가 그 노선 승하차지의 좌표 수정을 영구히
     * 막지 않는다. 자정을 넘겨 달리는 어제 운행일 회차는 여전히 잠근다.
     */
    @Test
    void 이틀_전_끝나지_않은_회차는_좌표_잠금에서_빠지고_어제_회차는_잠근다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sun", "to_academy", List.of(1L));
        Map<String, Object> 일번 = jdbcTemplate.queryForMap("SELECT name, lat, lng FROM stop WHERE id = 1");
        String 좌표_변경 = """
                {"stops":[{"stop_id":1,"name":"%s","lat":37.599999,"lng":%s}]}""".formatted(일번.get("name"), 일번.get("lng"));

        jdbcTemplate.update("UPDATE run SET service_date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1 WHERE id = 3");
        승하차지를_저장한다(관계자A_토큰(), routeId, 좌표_변경)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));

        jdbcTemplate.update("UPDATE run SET service_date = (now() AT TIME ZONE 'Asia/Seoul')::date - 2 WHERE id = 3");
        승하차지를_저장한다(관계자A_토큰(), routeId, 좌표_변경).andExpect(status().isOk());
    }

    /**
     * BR-363 — {@code stops} 배열의 {@code null} 원소는 {@code 500} 이 아니라 {@code 422} 다. {@code @Valid} 는 {@code null} 원소를 건너뛰어
     * 서비스의 {@code Item::stopId} 가 NPE 를 던졌다. 같은 종류의 {@code stop_ids}·{@code fixed_stop_ids} 는 {@code null} 원소가 {@code 422}
     * 로 떨어진다. 거절된 뒤 노선의 정차 순서는 그대로다.
     */
    @Test
    void 저장_본문의_stops_에_null_원소가_있으면_422_이고_노선은_그대로다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "fri", "from_academy", STOPS_OF_A);

        for (String body : new String[] {"{\"stops\":[null]}",
                "{\"stops\":[{\"stop_id\":1,\"name\":\"하나\",\"lat\":37.5,\"lng\":127.0},null]}"}) {
            승하차지를_저장한다(관계자A_토큰(), routeId, body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        assertThat(정차_순서(routeId)).containsExactlyElementsOf(STOPS_OF_A);
    }

    @Test
    void 남의_학원_노선의_승하차지는_저장할_수_없다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "mon", "from_academy", STOPS_OF_A);

        승하차지를_저장한다(관계자B_토큰(), routeId, "{\"stops\":[]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    // ── 최적화 기준점(2026-09-23 사용자 지시 — 위경도 입력칸 제거) ─────────────────────────────

    /**
     * 기준점을 주지 않으면 <b>방향 규칙</b>(Ruling 190 — 등원은 첫 승차지 → 학원)으로 정한다. 그 규칙대로
     * 기준점을 손으로 넣은 호출과 같은 순서가 나와야 한다.
     */
    @Test
    void 기준점을_주지_않으면_등원은_첫_승차지에서_학원으로_정해_최적화한다() throws Exception {
        List<Long> 처음_순서 = List.of(4L, 3L, 1L, 2L);
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", 처음_순서);
        최적화_본문으로(관계자A_토큰(), routeId, "{}").andExpect(status().isOk());
        List<Long> 규칙으로_정한_순서 = 정차_순서(routeId);

        수정한다(관계자A_토큰(), routeId, "{\"stop_ids\":[4,3,1,2]}").andExpect(status().isOk());
        Map<String, Object> 첫_승차지 = jdbcTemplate.queryForMap("SELECT lat, lng FROM stop WHERE id = 4");
        Map<String, Object> 학원 = jdbcTemplate.queryForMap("SELECT lat, lng FROM academy WHERE id = ?",
                ACADEMY_A_ID);
        최적화_본문으로(관계자A_토큰(), routeId, """
                {"origin":{"lat":%s,"lng":%s},"destination":{"lat":%s,"lng":%s}}"""
                .formatted(첫_승차지.get("lat"), 첫_승차지.get("lng"), 학원.get("lat"), 학원.get("lng")))
                .andExpect(status().isOk());

        assertThat(정차_순서(routeId)).isEqualTo(규칙으로_정한_순서);
    }

    // ── 자리 고정(2026-09-23 사용자 지시 — 특정 순서나 시점·종점을 고정) ──────────────────────

    /**
     * 고정한 승하차지는 <b>지금 자리</b>를 지키고 나머지만 다시 매긴다. [4,3,2,1] 을 그냥 최적화하면
     * [1,2,3,4] 가 되므로(위 시험), 4(시점)·1(종점)을 고정하면 그 둘이 제자리에 남는지로 고정이 실제로 먹었는지 가린다.
     */
    @Test
    void 고정한_승하차지는_지금_자리를_지키고_나머지만_다시_매긴다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "fri", "from_academy", List.of(4L, 3L, 2L, 1L));

        최적화_본문으로(관계자A_토큰(), routeId, """
                {"origin":{"lat":37.565000,"lng":126.977000},
                 "destination":{"lat":37.570500,"lng":126.982000},
                 "fixed_stop_ids":[4,1]}""")
                .andExpect(status().isOk());

        List<Long> 순서 = 정차_순서(routeId);
        assertThat(순서.getFirst()).isEqualTo(4L);
        assertThat(순서.getLast()).isEqualTo(1L);
        assertThat(순서).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    /** 노선에 없는 승하차지를 고정하라는 요청은 받지 않는다 — 조용히 무시하면 관계자는 고정됐다고 믿는다. */
    @Test
    void 노선에_없는_승하차지를_고정하면_422_다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "sat", "from_academy", List.of(4L, 3L));

        최적화_본문으로(관계자A_토큰(), routeId, "{\"fixed_stop_ids\":[1]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 학원 좌표가 없으면 다른 점으로 대신하지 않는다(Ruling 190) — {@code 500} 이 아니라 이유가 담긴 {@code 422} 다. */
    @Test
    void 기준점을_주지_않았는데_학원_좌표가_없으면_422_다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "wed", "from_academy", STOPS_OF_A);
        jdbcTemplate.update("UPDATE academy SET lat = NULL, lng = NULL WHERE id = ?", ACADEMY_A_ID);

        최적화_본문으로(관계자A_토큰(), routeId, "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_COORDINATES_MISSING"));
    }

    /** 기준점을 하나만 주면 규칙과 요청이 섞인다 — 어느 쪽인지 모르는 산출이 되므로 받지 않는다. */
    @Test
    void 기준점을_하나만_주면_422_다() throws Exception {
        long routeId = 편성된_노선_id(관계자A_토큰(), BUS_A_ID, "thu", "from_academy", STOPS_OF_A);

        최적화_본문으로(관계자A_토큰(), routeId, "{\"origin\":{\"lat\":37.5,\"lng\":127.0}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 시드의 운행 중 회차(R3)를 끝낸다 — 승하차지 좌표 수정이 운행 중 잠금(BR-052)에 걸리지 않는 상태를 만든다. */
    private void 운행_중_회차를_끝낸다() {
        jdbcTemplate.update("UPDATE run SET status = 'finished', finished_at = now() WHERE status = 'moving'");
    }

    private ResultActions 승하차지를_저장한다(String token, long routeId, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/staff/routes/{id}/stops", routeId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 최적화_본문으로(String token, long routeId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/routes/" + routeId + "/optimize")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long 편성된_노선_id(String token, long busId, String weekday, String direction, List<Long> stopIds)
            throws Exception {
        MvcResult result = 편성한다(token, busId, weekday, direction, stopIds)
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(본문(result), "$.data.id"));
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

    private String 목록_본문(String token, String query) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/routes?" + query).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** 이름만 갈라 편성한다 — 정렬 시험이 이름 차례를 봐야 해서 이름을 밖에서 준다. */
    private void 이름을_주어_편성한다(String name, long busId, String weekday, String direction)
            throws Exception {
        mockMvc.perform(post("/api/v1/staff/routes")
                        .header("Authorization", 관계자A_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bus_id":%d,"weekday":"%s","direction":"%s","name":"%s","stop_ids":[]}"""
                                .formatted(busId, weekday, direction, name)))
                .andExpect(status().isCreated());
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
