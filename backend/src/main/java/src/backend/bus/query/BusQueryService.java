package src.backend.bus.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.dto.BusDetailResponse;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/** 버스 목록/상세 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class BusQueryService {

    private final BusRepository busRepository;
    private final StudentRepository studentRepository;

    public BusQueryService(BusRepository busRepository, StudentRepository studentRepository) {
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public List<BusResponse> listBuses(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return busRepository.findByTenantId(effectiveTenant).stream()
                .map(bus -> BusResponse.of(bus, onboardCount(bus.getId())))
                .toList();
    }

    /**
     * 기사 본인의 담당 버스. 기사용 API(노선 조회·위치 보고·승하차)가 전부 busId 를 입력으로 받는데
     * 기사가 그걸 알아낼 수단이 없어 추가했다 — 기사 앱은 로그인 직후 1회 호출해 busId 를 캐시한다.
     */
    @Transactional(readOnly = true)
    public BusResponse getMyBus(AuthUser driver) {
        return busRepository.findByDriverIdOrderByIdAsc(driver.userId()).stream()
                .findFirst()
                .map(bus -> BusResponse.of(bus, onboardCount(bus.getId())))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "담당 버스가 없습니다"));
    }

    @Transactional(readOnly = true)
    public BusDetailResponse getBus(AuthUser admin, Long busId) {
        Bus bus = loadAccessibleBus(admin, busId);
        List<Student> roster = studentRepository.findByAssignedBusId(bus.getId());
        List<BusDetailResponse.RosterEntry> entries = roster.stream()
                .map(s -> new BusDetailResponse.RosterEntry(s.getId(), s.getName()))
                .toList();
        return new BusDetailResponse(BusResponse.of(bus, roster.size()), entries);
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
}
