package src.backend.route.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.route.dto.CreateRouteRequest;
import src.backend.route.dto.CreateStopRequest;
import src.backend.route.dto.RouteResponse;
import src.backend.route.dto.StopResponse;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.route.repository.spec.StopRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/** 노선 생성 + 정류장 추가 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class RouteCommandService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final TenantRepository tenantRepository;

    public RouteCommandService(RouteRepository routeRepository,
                               StopRepository stopRepository,
                               TenantRepository tenantRepository) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public RouteResponse createRoute(AuthUser admin, CreateRouteRequest req) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, req.tenantId());
        Tenant tenant = tenantRepository.findById(effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
        Route saved = routeRepository.save(Route.builder()
                .tenant(tenant)
                .name(req.name())
                .assignCapacity(req.assignCapacity())
                .build());
        return RouteResponse.of(saved, 0);
    }

    @Transactional
    public StopResponse addStop(AuthUser admin, Long routeId, CreateStopRequest req) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "노선을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, route.getTenant().getId()); // 접근 권한 검증
        Stop saved = stopRepository.save(Stop.builder()
                .route(route)
                .name(req.name())
                .seq(req.seq())
                .lat(req.lat())
                .lng(req.lng())
                .build());
        return StopResponse.from(saved);
    }
}
