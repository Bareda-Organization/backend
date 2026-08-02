package src.backend.location.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.dto.BusLocationView;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * 버스 단위 실시간 위치 조회 — 학생 단위 {@link LocationQueryService}와 나란한 미러(F1).
 * 관리자 관제(지도 표시)와 학부모 초기 렌더(P1) 두 갈래를 담당한다. CQRS 원칙(§7)에 따라
 * {@link src.backend.location.command.BusLocationCommandService}를 호출하지 않는다.
 */
@Service
public class BusLocationQueryService {

    private final BusLocationRepository busLocationRepository;
    private final BusRepository busRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public BusLocationQueryService(BusLocationRepository busLocationRepository, BusRepository busRepository,
                                   StudentGuardianRepository studentGuardianRepository) {
        this.busLocationRepository = busLocationRepository;
        this.busRepository = busRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<BusLocationView> getTenantBusLocations(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<Bus> buses = busRepository.findByTenantId(effectiveTenant);
        return buses.stream()
                .flatMap(bus -> busLocationRepository.findLatest(bus.getId()).stream()
                        .map(ping -> BusLocationView.of(bus.getName(), ping)))
                .toList();
    }

    /**
     * 자녀가 배정된 버스들의 최신 위치. 미배정 자녀는 건너뛰고, 형제가 같은 버스면 1건으로 합친다.
     * 입력이 보호자 계정 하나뿐이라 다른 학부모 자녀의 버스가 결과에 섞일 경로가 없다.
     */
    @Transactional(readOnly = true)
    public List<BusLocationView> getChildrenBusLocations(AuthUser parent) {
        List<Bus> buses = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(sg -> sg.getStudent().getAssignedBus())
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Bus::getId, bus -> bus, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
        return buses.stream()
                .flatMap(bus -> busLocationRepository.findLatest(bus.getId()).stream()
                        .map(ping -> BusLocationView.of(bus.getName(), ping)))
                .toList();
    }
}
