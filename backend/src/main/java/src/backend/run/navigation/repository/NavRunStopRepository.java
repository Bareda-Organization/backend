package src.backend.run.navigation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.run.navigation.dto.NavStopRow;
import src.backend.run.navigation.entity.NavRunStop;

/**
 * {@link NavRunStop} 영속성 접근 — 이 인터페이스가 선언하는 유일한 조회는 정차지·경유 지점 이름·
 * 좌표까지 합친 투영 하나다(API_SPEC §4.16). 그 외 CRUD 메서드가 없는 이유는 이 모듈이 {@code run_stop}
 * 에 쓰지 않기 때문이다 — 상속된 {@code JpaRepository} 의 쓰기 메서드는 실질적으로 죽은 표면이다.
 */
public interface NavRunStopRepository extends JpaRepository<NavRunStop, Long> {

    /**
     * 그 배포 버전의 정차 목록을 순번(RTE-01 순서) 그대로, 이름·좌표를 채워서 읽는다.
     *
     * <p>제외(스킵·도착 완료)는 여기서 걸러내지 않는다 — 이 조회는 "그 버전에 실린 전부"를 그대로
     * 돌려주고, 무엇을 뺄지는 호출부({@code NavigationQueryService})가 API_SPEC §4.16 의 두 규칙을
     * 판단해서 정한다. 필터를 여기 심으면 "왜 뺐는지" 를 검사하는 시험이 SQL 조건과 서비스 로직
     * 둘로 흩어진다.
     */
    @Query("SELECT new src.backend.run.navigation.dto.NavStopRow(rs.id, rs.seq, rs.change, "
            + "CASE WHEN rs.arrivedAt IS NOT NULL THEN true ELSE false END, rs.stopId, "
            + "COALESCE(s.name, w.label), COALESCE(s.lat, w.lat), COALESCE(s.lng, w.lng)) "
            + "FROM NavRunStop rs LEFT JOIN Stop s ON s.id = rs.stopId LEFT JOIN Waypoint w ON w.id = rs.waypointId "
            + "WHERE rs.routeVersionId = :routeVersionId ORDER BY rs.seq ASC")
    List<NavStopRow> findAllByRouteVersionIdOrderBySeqAsc(@Param("routeVersionId") Long routeVersionId);
}
