package src.backend.routing.query;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;

/**
 * 노선 계획 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.routing.command.RoutingCommandService}를 호출하지 않는다.
 */
@Service
public class RoutingQueryService {

    private final RoutePlanRepository routePlanRepository;
    private final BusRepository busRepository;

    public RoutingQueryService(RoutePlanRepository routePlanRepository, BusRepository busRepository) {
        this.routePlanRepository = routePlanRepository;
        this.busRepository = busRepository;
    }

    @Transactional(readOnly = true)
    public RoutePlanResponse get(AuthUser admin, Long id) {
        RoutePlan plan = routePlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "노선 계획을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, plan.getTenantId()); // 접근 권한 검증
        return RoutePlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public List<RoutePlanResponse> list(AuthUser admin, Long tenantId, Long busId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<RoutePlan> plans = busId != null
                ? routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(effectiveTenant, busId)
                : routePlanRepository.findByTenantIdOrderByCreatedAtDesc(effectiveTenant);
        return plans.stream().map(RoutePlanResponse::from).toList();
    }

    /** 기사: 담당 버스의 당일 배포 완료 노선(등원/하원, Phase 6f). rideevent 와 동일하게 담당 기사만 허용한다. */
    @Transactional(readOnly = true)
    public List<RoutePlanResponse> getPublishedForDriver(AuthUser driver, Long busId, LocalDate serviceDate) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);
        LocalDate date = serviceDate != null ? serviceDate : LocalDate.now();
        return routePlanRepository
                .findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(busId, date, RoutePlanStatus.PUBLISHED)
                .stream().map(RoutePlanResponse::from).toList();
    }

    private void requireAssignedDriver(Bus bus, AuthUser driver) {
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 조회할 수 있습니다");
        }
    }
}
