package src.backend.location.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.dto.BusLocationView;
import src.backend.location.repository.spec.BusLocationRepository;

/**
 * 버스 단위 실시간 위치 조회 — 학생 단위 {@link LocationQueryService}와 나란한 미러(F1).
 * 관리자 관제(지도 표시) 용도라 관리자 조회 하나만 둔다. CQRS 원칙(§7)에 따라
 * {@link src.backend.location.command.BusLocationCommandService}를 호출하지 않는다.
 */
@Service
public class BusLocationQueryService {

    private final BusLocationRepository busLocationRepository;
    private final BusRepository busRepository;

    public BusLocationQueryService(BusLocationRepository busLocationRepository, BusRepository busRepository) {
        this.busLocationRepository = busLocationRepository;
        this.busRepository = busRepository;
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
}
