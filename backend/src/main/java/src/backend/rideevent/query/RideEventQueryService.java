package src.backend.rideevent.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.access.BusCrewGuard;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 승하차 기록 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.rideevent.command.RideEventCommandService}를 호출하지 않고,
 * 담당자 검사는 {@link src.backend.bus.access.BusCrewGuard}에 위임한다.
 */
@Service
public class RideEventQueryService {

    private final RideEventRepository rideEventRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public RideEventQueryService(RideEventRepository rideEventRepository,
                                 BusRepository busRepository,
                                 StudentRepository studentRepository,
                                 StudentGuardianRepository studentGuardianRepository) {
        this.rideEventRepository = rideEventRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<RideEventResponse> getMyRecords(AuthUser student, LocalDate date) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        LocalDateTime[] range = dayRange(date);
        return toResponses(rideEventRepository
                .findByStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(me.getId(), range[0], range[1]));
    }

    @Transactional(readOnly = true)
    public List<RideEventResponse> getChildrenRecords(AuthUser parent, LocalDate date) {
        List<Long> studentIds = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .map(Student::getId)
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime[] range = dayRange(date);
        return toResponses(rideEventRepository
                .findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(studentIds, range[0], range[1]));
    }

    @Transactional(readOnly = true)
    public List<RideEventResponse> getRosterRecords(AuthUser actor, Long busId, LocalDate date) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        BusCrewGuard.requireAssignedCrew(bus, actor);
        LocalDateTime[] range = dayRange(date);
        return toResponses(rideEventRepository
                .findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(busId, range[0], range[1]));
    }

    @Transactional(readOnly = true)
    public List<RideEventResponse> getTenantRecords(AuthUser admin, Long tenantId, Long studentId,
                                                     LocalDate from, LocalDate to) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<RideEvent> events = (studentId != null)
                ? rideEventRepository.findByTenantIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        effectiveTenant, studentId, start, end)
                : rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        effectiveTenant, start, end);
        return toResponses(events);
    }

    private LocalDateTime[] dayRange(LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return new LocalDateTime[]{day.atStartOfDay(), day.plusDays(1).atStartOfDay()};
    }

    private List<RideEventResponse> toResponses(List<RideEvent> events) {
        return events.stream().map(RideEventResponse::from).toList();
    }
}
