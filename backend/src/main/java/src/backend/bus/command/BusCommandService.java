package src.backend.bus.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.dto.AssignmentRequest;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.CreateBusRequest;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.route.entity.Route;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/** 버스 생성/배차 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. 학원 격리는 TenantGuard 로 검사한다. */
@Service
public class BusCommandService {

    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final RouteRepository routeRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;

    public BusCommandService(BusRepository busRepository,
                             StudentRepository studentRepository,
                             RouteRepository routeRepository,
                             TenantRepository tenantRepository,
                             UserRepository userRepository,
                             UserTenantRoleRepository userTenantRoleRepository) {
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.routeRepository = routeRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
    }

    @Transactional
    public BusResponse createBus(AuthUser admin, CreateBusRequest req) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, req.tenantId());
        Tenant tenant = tenantRepository.findById(effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));

        Route route = req.routeId() != null ? loadRouteInTenant(req.routeId(), effectiveTenant) : null;
        User driver = req.driverId() != null
                ? loadUserWithRole(req.driverId(), effectiveTenant, Role.DRIVER)
                : null;

        Bus saved = busRepository.save(Bus.builder()
                .tenant(tenant)
                .name(req.name())
                .plateNumber(req.plateNumber())
                .seatCapacity(req.seatCapacity())
                .driver(driver)
                .route(route)
                .insuranceExpiry(req.insuranceExpiry())
                .build());
        return BusResponse.of(saved, onboardCount(saved.getId()));
    }

    @Transactional
    public BusResponse assign(AuthUser admin, Long busId, AssignmentRequest req) {
        Bus bus = loadAccessibleBus(admin, busId);
        Long tenantId = bus.getTenant().getId();
        if (req.driverId() != null) {
            bus.assignDriver(loadUserWithRole(req.driverId(), tenantId, Role.DRIVER));
        }
        if (req.attendantId() != null) {
            bus.assignAttendant(loadUserWithRole(req.attendantId(), tenantId, Role.ATTENDANT));
        }
        if (req.routeId() != null) {
            bus.assignRoute(loadRouteInTenant(req.routeId(), tenantId));
        }
        return BusResponse.of(bus, onboardCount(bus.getId()));
    }

    private int onboardCount(Long busId) {
        return studentRepository.findByAssignedBusId(busId).size();
    }

    private Bus loadAccessibleBus(AuthUser admin, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, bus.getTenant().getId()); // 접근 권한 검증
        return bus;
    }

    private Route loadRouteInTenant(Long routeId, Long tenantId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "노선을 찾을 수 없습니다"));
        if (!route.getTenant().getId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "노선이 이 학원 소속이 아닙니다");
        }
        return route;
    }

    /** 그 학원에서 요구 역할을 가진 사용자만 통과시킨다 — 역할·테넌트 둘 다 검사한다. */
    private User loadUserWithRole(Long userId, Long tenantId, Role required) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다"));
        boolean granted = userTenantRoleRepository.findByUserId(userId).stream()
                .anyMatch(utr -> utr.getRole() == required
                        && utr.getTenant() != null && utr.getTenant().getId().equals(tenantId));
        if (!granted) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 학원의 " + required.name() + " 계정이 아닙니다(userId=" + userId + ")");
        }
        return user;
    }
}
