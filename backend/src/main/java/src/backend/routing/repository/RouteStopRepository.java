package src.backend.routing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
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

    /**
     * 목록(§5.9 {@code stop_count})이 쓰는 편성별 정차지 수 — 한 쪽의 편성 전부를 한 번에 센다. 편성마다
     * 세면 한 쪽(최대 100건)이 질의 100건이 된다. 정차지가 없는 편성은 결과에 없다(호출부가 0 으로 채운다).
     * 학원 조건은 {@link #findAllOrderedByRouteIdAndAcademyId} 와 같은 이유로 {@code route} 조인에 둔다.
     */
    @Query("""
            SELECT rs.routeId AS routeId, COUNT(rs) AS total FROM RouteStop rs, Route r
            WHERE rs.routeId = r.id AND r.academyId = :academyId AND r.id IN :routeIds
            GROUP BY rs.routeId
            """)
    List<StopCount> countByRouteIdsAndAcademyId(@Param("routeIds") Collection<Long> routeIds,
            @Param("academyId") Long academyId);

    /**
     * 승하차지 관리 목록(§5.9, Ruling 849)이 승하차지마다 싣는 편성 — 한 쪽의 승하차지 전부를 한 번에 읽는다. 승하차지마다 읽으면 한
     * 쪽(최대 100건)이 질의 100건이 된다. 비활성 편성도 싣는다. 학원 조건은 {@link #findAllOrderedByRouteIdAndAcademyId} 와 같은
     * 이유로 {@code route} 조인에 둔다.
     */
    @Query("""
            SELECT rs.stopId AS stopId, r.id AS routeId, r.busId AS busId, r.weekday AS weekday,
                   r.direction AS direction, r.active AS active
            FROM RouteStop rs, Route r
            WHERE rs.routeId = r.id AND r.academyId = :academyId AND rs.stopId IN :stopIds
            ORDER BY r.id
            """)
    List<StopRoute> findRoutesByStopIdsAndAcademyId(@Param("stopIds") Collection<Long> stopIds,
            @Param("academyId") Long academyId);

    /** {@link #findRoutesByStopIdsAndAcademyId} 의 한 행 — 승하차지 하나를 담은 편성 하나. */
    interface StopRoute {

        Long getStopId();

        Long getRouteId();

        Long getBusId();

        Weekday getWeekday();

        Direction getDirection();

        boolean getActive();
    }

    /** {@link #countByRouteIdsAndAcademyId} 의 한 행 — 편성 id 와 그 편성의 정차지 수. */
    interface StopCount {

        Long getRouteId();

        long getTotal();
    }
}
