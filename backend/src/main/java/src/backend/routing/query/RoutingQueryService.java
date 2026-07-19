package src.backend.routing.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
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

    public RoutingQueryService(RoutePlanRepository routePlanRepository) {
        this.routePlanRepository = routePlanRepository;
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
}
