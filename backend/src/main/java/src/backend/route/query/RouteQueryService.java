package src.backend.route.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.route.dto.RouteResponse;
import src.backend.route.dto.StopResponse;
import src.backend.route.entity.Route;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.repository.spec.StudentRepository;

/** 노선 목록(정원 초과 경고 포함) + 정류장 목록 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class RouteQueryService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;

    public RouteQueryService(RouteRepository routeRepository,
                             StopRepository stopRepository,
                             BusRepository busRepository,
                             StudentRepository studentRepository) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> listRoutes(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return routeRepository.findByTenantId(effectiveTenant).stream()
                .map(route -> RouteResponse.of(route, assignedCount(route)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StopResponse> getStops(Long routeId) {
        return stopRepository.findByRouteIdOrderBySeqAsc(routeId).stream()
                .map(StopResponse::from)
                .toList();
    }

    /** 이 노선을 운행하는 버스들에 배정된 학생 수 합계(정원 초과 판정용). */
    private int assignedCount(Route route) {
        return busRepository.findByTenantId(route.getTenant().getId()).stream()
                .filter(bus -> bus.getRoute() != null && bus.getRoute().getId().equals(route.getId()))
                .mapToInt(bus -> studentRepository.findByAssignedBusIdAndActiveTrue(bus.getId()).size())
                .sum();
    }
}
