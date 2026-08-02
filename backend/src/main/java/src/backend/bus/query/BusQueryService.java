package src.backend.bus.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import src.backend.route.entity.Stop;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/** 버스 목록/상세 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class BusQueryService {

    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final RoutePlanRepository routePlanRepository;

    public BusQueryService(BusRepository busRepository,
                           StudentRepository studentRepository,
                           StudentGuardianRepository studentGuardianRepository,
                           RoutePlanRepository routePlanRepository) {
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.routePlanRepository = routePlanRepository;
    }

    /** 학원 버스 목록. 버스마다 학생을 세면 N+1 이라 배정 학생을 한 번에 읽어 버스별로 접는다. */
    @Transactional(readOnly = true)
    public List<BusResponse> listBuses(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<Bus> buses = busRepository.findByTenantId(effectiveTenant);
        Map<Long, Long> onboardByBus = studentRepository
                .findByAssignedBusIdInAndActiveTrue(buses.stream().map(Bus::getId).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.getAssignedBus().getId(), Collectors.counting()));
        return buses.stream()
                .map(bus -> BusResponse.of(bus, onboardByBus.getOrDefault(bus.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * 기사·선탑자 본인의 담당 버스. 담당자용 API(노선 조회·위치 보고·승하차)가 전부 busId 를 입력으로 받는데
     * 담당자가 그걸 알아낼 수단이 없어 추가했다 — 담당자 앱은 로그인 직후 1회 호출해 busId 를 캐시한다.
     * 개인정보 밀도가 높은 상세 응답은 여기서 재사용하지 않는다(관리자 전용).
     */
    @Transactional(readOnly = true)
    public BusResponse getMyBus(AuthUser crew) {
        // 기사 배정을 먼저 보고, 없으면 선탑자 배정을 본다(한 사람이 둘 다인 경우는 없다).
        return busRepository.findByDriverIdOrderByIdAsc(crew.userId()).stream()
                .findFirst()
                .or(() -> busRepository.findByAttendantIdOrderByIdAsc(crew.userId()).stream().findFirst())
                .map(bus -> BusResponse.of(bus, onboardCount(bus.getId())))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "담당 버스가 없습니다"));
    }

    /**
     * 관제용 버스 상세 — 버스1 + 학생1 + 보호자1 + 계획1 = 쿼리 4회 고정.
     * 저장소에서 개인정보 밀도가 가장 높은 응답이라 관리자 권한(컨트롤러) + TenantGuard(loadAccessibleBus)를
     * 반드시 먼저 통과시킨다.
     */
    @Transactional(readOnly = true)
    public BusDetailResponse getBus(AuthUser admin, Long busId, LocalDate date) {
        Bus bus = loadAccessibleBus(admin, busId);                       // TenantGuard 통과
        LocalDate serviceDate = date != null ? date : LocalDate.now();
        List<Student> roster = studentRepository.findByAssignedBusIdAndActiveTrue(bus.getId());
        Map<Long, List<StudentGuardian>> guardiansByStudent = roster.isEmpty() ? Map.of()
                : studentGuardianRepository
                        .findWithGuardianByStudentIdIn(roster.stream().map(Student::getId).toList()).stream()
                        .collect(Collectors.groupingBy(sg -> sg.getStudent().getId()));
        List<BusDetailResponse.RoutePlanView> plans = routePlanRepository
                .findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(bus.getId(), serviceDate, RoutePlanStatus.PUBLISHED)
                .stream().map(this::toPlanView).toList();
        List<BusDetailResponse.RosterEntry> entries = roster.stream()
                .map(s -> toRosterEntry(s, guardiansByStudent.getOrDefault(s.getId(), List.of()))).toList();
        return new BusDetailResponse(BusResponse.of(bus, roster.size()), serviceDate, plans, entries);
    }

    private BusDetailResponse.RoutePlanView toPlanView(RoutePlan plan) {
        List<BusDetailResponse.RoutePlanView.StopView> stops = plan.getStops().stream()
                .map(s -> new BusDetailResponse.RoutePlanView.StopView(
                        s.getSeq(), s.getStudentId(), s.getLat(), s.getLng(), s.getEtaSeconds()))
                .toList();
        return new BusDetailResponse.RoutePlanView(
                plan.getId(), plan.getDirection(), plan.getVersion(),
                plan.getTotalDistanceM(), plan.getTotalDurationS(), plan.getPolyline(), stops);
    }

    private BusDetailResponse.RosterEntry toRosterEntry(Student student, List<StudentGuardian> guardians) {
        // 등원 좌표는 학생 전용 좌표가 있으면 그것을, 없으면 공유 정류장(boardingStop)을 쓴다(D-K).
        Stop stop = student.getBoardingStop();
        String pickupLabel = student.getPickupLat() != null ? student.getPickupAddress()
                : (stop != null ? stop.getName() : null);
        Double pickupLat = student.getPickupLat() != null ? student.getPickupLat()
                : (stop != null ? stop.getLat() : null);
        Double pickupLng = student.getPickupLat() != null ? student.getPickupLng()
                : (stop != null ? stop.getLng() : null);
        List<BusDetailResponse.GuardianView> guardianViews = guardians.stream()
                .map(sg -> new BusDetailResponse.GuardianView(
                        sg.getGuardian().getId(), sg.getGuardian().getName(),
                        sg.getGuardian().getPhone(), sg.getRelation()))
                .toList();
        return new BusDetailResponse.RosterEntry(
                student.getId(), student.getName(), student.getPhotoUrl(), student.getPhone(),
                pickupLabel, pickupLat, pickupLng,
                student.getDropoffAddress(), student.getDropoffLat(), student.getDropoffLng(),
                guardianViews);
    }

    private int onboardCount(Long busId) {
        return studentRepository.findByAssignedBusIdAndActiveTrue(busId).size();
    }

    private Bus loadAccessibleBus(AuthUser admin, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, bus.getTenant().getId()); // 접근 권한 검증
        return bus;
    }
}
