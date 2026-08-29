package src.backend.routing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.routing.entity.RouteStop;

/**
 * {@link RouteStop} 영속성 접근 — {@code route_stop} 은 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이라(ERD §6.1), 학원 조건을 붙일 자리가 {@code route} 조인뿐이다.
 */
public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {

    /**
     * 한 편성의 정차 순서를 순번 차례로 읽는다(RTE-01 · RTE-09, §5.9).
     *
     * <p>{@code routeId} 만으로 찾지 않고 <b>{@code route} 를 조인해 학원까지 대조</b>하는 이유는 이
     * 테이블에 학원 컬럼이 부재하기 때문이다 — 호출부가 편성을 먼저 학원으로 좁혀 꺼내더라도, 자식
     * 단독 조회 경로를 열어 두면 다음 사람이 그 순서를 지키지 않은 채 이 메서드를 부른다(ERD §6.2).
     *
     * <p><b>{@code ORDER BY seq} 가 계약의 일부다.</b> 빼면 DB 가 돌려주는 임의 순서가 그대로 편성
     * 순서로 실려, 기사 화면의 정차 차례가 조회 시점마다 달라진다.
     */
    @Query("""
            SELECT rs FROM RouteStop rs, Route r
            WHERE rs.routeId = r.id AND r.id = :routeId AND r.academyId = :academyId
            ORDER BY rs.seq
            """)
    List<RouteStop> findAllOrderedByRouteIdAndAcademyId(@Param("routeId") Long routeId,
            @Param("academyId") Long academyId);
}
