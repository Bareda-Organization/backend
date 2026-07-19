package src.backend.routing.repository.spec;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.routing.domain.RouteDirection;
import src.backend.routing.entity.RoutePlan;

public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    Optional<RoutePlan> findTopByBusIdAndDirectionOrderByVersionDesc(Long busId, RouteDirection direction);

    List<RoutePlan> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<RoutePlan> findByTenantIdAndBusIdOrderByCreatedAtDesc(Long tenantId, Long busId);
}
