package src.backend.routing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.routing.entity.RouteStop;

/**
 * {@code route_stop} 조회의 학원 조건이 <b>JPQL 조인 안에</b> 있음을 컨트롤러·서비스를 거치지 않고 보인다.
 *
 * <p><b>이 클래스가 있는 이유가 게이트 리뷰의 지적이다(수정 라운드 1 Important 1).</b> 조인 조건
 * {@code AND r.academyId = :academyId} 를 지워도 그 전에는 아무 테스트도 실패하지 않았다. 두 가지가
 * 겹쳐서다.
 * <ul>
 *   <li>{@code AcademyScopeRepositoryConventionTest} 는 <b>메서드 이름의 {@code AcademyId} 부분
 *       문자열</b>과 {@code @Query} 본문의 문자열 유무만 본다({@code AcademyScopeRule}) — 조인 조건이
 *       실제로 걸렸는지는 판정 대상 밖이다. 그 클래스의 자바독이 "부모 조인을 늘리면 이름만 적어
 *       통과시키는 회피가 성립한다" 고 예고한 바로 그 형태다</li>
 *   <li>HTTP 왕복 시험도 통과한다 — 두 호출부({@code RouteDetailAssembler}·
 *       {@code RouteOptimizeService})가 상위 {@code Route} 를 {@code findByIdAndAcademyId} 로 <b>먼저</b>
 *       좁혀서, 이 조인의 격리 실패가 응답까지 도달하지 않는다</li>
 * </ul>
 *
 * <p>즉 이 조인은 <b>방어 심층의 안쪽 층</b>이고, 안쪽 층은 바깥 층이 성한 동안 어떤 왕복 시험으로도
 * 관측되지 않는다. 그래서 저장소를 직접 부른다.
 */
@SpringBootTest
@Transactional
class RouteStopRepositoryAcademyScopeTest {

    private static final long ACADEMY_A_ID = 1L;

    private static final long ACADEMY_B_ID = 2L;

    /** 시드 학원 B 의 1호차와 승하차지 — 학원 B 편성을 세우는 재료다. */
    private static final long BUS_B_ID = 3L;

    private static final long STOP_OF_B = 5L;

    /** 시드 학원 A 의 2호차 — 순번 정렬 시험용 편성을 세운다(시드 편성과 조합이 겹치지 않는다). */
    private static final long BUS_A_ID = 2L;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 타 학원 편성의 정차 순서는 <b>실재하지만</b> 그 학원 범위에서만 나타난다.
     *
     * <p>대조군(학원 B 로 물으면 나온다)을 함께 보지 않으면 "0건" 이 격리의 결과인지 원래 행이 없는
     * 것인지 갈리지 않는다 — 그 상태에서는 조인 조건을 지워도 이 단언이 통과한다.
     */
    @Test
    void 타_학원_편성의_정차_순서는_실재하지만_그_학원_범위에서만_나타난다() {
        long routeOfB = 편성행을_만든다(ACADEMY_B_ID, BUS_B_ID, "mon", "to_academy");
        정차행을_만든다(routeOfB, STOP_OF_B, 1);

        assertThat(routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeOfB, ACADEMY_B_ID))
                .as("대조군이 0건이면 아래 부재 단언은 아무것도 검사하지 않는다")
                .isNotEmpty();
        assertThat(routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeOfB, ACADEMY_A_ID))
                .as("학원 A 로 물었는데 학원 B 편성의 정차 순서가 나온다 — 조인의 학원 조건이 사라졌다")
                .isEmpty();
    }

    /**
     * 순번 차례로 돌려주는 것이 이 조회의 계약이다 — 편성의 내용이 곧 순서라서다.
     *
     * <p>행을 <b>순번과 어긋난 차례로</b> 넣고 확인한다. 순번대로 넣으면 {@code ORDER BY} 를 지워도
     * PostgreSQL 이 대개 삽입 차례로 돌려주어 통과한다 — 그러면 이 단언이 정렬을 고정하지 못한다.
     */
    @Test
    void 정차_순서는_순번_차례로_돌아온다() {
        long routeOfA = 편성행을_만든다(ACADEMY_A_ID, BUS_A_ID, "mon", "to_academy");
        정차행을_만든다(routeOfA, 3L, 3);
        정차행을_만든다(routeOfA, 1L, 1);
        정차행을_만든다(routeOfA, 2L, 2);

        List<RouteStop> ordered = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(
                routeOfA, ACADEMY_A_ID);

        assertThat(ordered).extracting(RouteStop::getSeq).containsExactly(1, 2, 3);
        assertThat(ordered).extracting(RouteStop::getStopId).containsExactly(1L, 2L, 3L);
    }

    private long 편성행을_만든다(long academyId, long busId, String weekday, String direction) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO route (academy_id, bus_id, weekday, direction, name, active)
                VALUES (?, ?, ?, ?, '조인 시험 편성', true) RETURNING id
                """, Long.class, academyId, busId, weekday, direction);
    }

    private void 정차행을_만든다(long routeId, long stopId, int seq) {
        jdbcTemplate.update("INSERT INTO route_stop (route_id, stop_id, seq) VALUES (?, ?, ?)",
                routeId, stopId, seq);
    }
}
