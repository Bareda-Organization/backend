package src.backend.route.service.impl;

import src.backend.route.service.spec.RouteService;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.repository.spec.BusRepository;
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
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/**
 * {@link RouteService} 기본 구현 — 목록/생성 + 정류장 추가 + 정원 초과 경고 판정(기능9).
 */
@Service
public class RouteServiceImpl implements RouteService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final TenantRepository tenantRepository;

    public RouteServiceImpl(RouteRepository routeRepository,
                            StopRepository stopRepository,
                            BusRepository busRepository,
                            StudentRepository studentRepository,
                            TenantRepository tenantRepository) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteResponse> listRoutes(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return routeRepository.findByTenantId(effectiveTenant).stream()
                .map(route -> RouteResponse.of(route, assignedCount(route)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StopResponse> getStops(Long routeId) {
        return stopRepository.findByRouteIdOrderBySeqAsc(routeId).stream()
                .map(StopResponse::from)
                .toList();
    }

    @Override
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

    @Override
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

    /** 이 노선을 운행하는 버스들에 배정된 학생 수 합계(정원 초과 판정용). */
    private int assignedCount(Route route) {
        return busRepository.findByTenantId(route.getTenant().getId()).stream()
                .filter(bus -> bus.getRoute() != null && bus.getRoute().getId().equals(route.getId()))
                .mapToInt(bus -> studentRepository.findByAssignedBusId(bus.getId()).size())
                .sum();
    }
}
