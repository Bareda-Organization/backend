package src.backend.route.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.route.entity.Stop;

/**
 * 정류장 저장소. 노선 안에서 seq(순서)대로 정렬해 조회한다.
 */
public interface StopRepository extends JpaRepository<Stop, Long> {

    List<Stop> findByRouteIdOrderBySeqAsc(Long routeId);
}
