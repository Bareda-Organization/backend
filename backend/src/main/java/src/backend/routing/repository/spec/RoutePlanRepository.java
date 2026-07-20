package src.backend.routing.repository.spec;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;

public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    Optional<RoutePlan> findTopByBusIdAndDirectionOrderByVersionDesc(Long busId, RouteDirection direction);

    List<RoutePlan> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<RoutePlan> findByTenantIdAndBusIdOrderByCreatedAtDesc(Long tenantId, Long busId);

    /** 기사 조회(Phase 6f) — 담당 버스의 당일 배포 완료 계획만(등원/하원 최대 2건). */
    List<RoutePlan> findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(
            Long busId, LocalDate serviceDate, RoutePlanStatus status);
}
