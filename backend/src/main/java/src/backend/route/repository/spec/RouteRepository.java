package src.backend.route.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.route.entity.Route;

/**
 * 노선 저장소. 학원(테넌트) 단위 조회.
 */
public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByTenantId(Long tenantId);
}
