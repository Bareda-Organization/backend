package src.backend.rideevent.service.impl;

import src.backend.rideevent.service.spec.RideEventService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.service.spec.NotificationService;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * {@link RideEventService} 기본 구현 — 승하차 기록의 핵심 로직.
 * - 기사가 담당 버스의 학생 승/하차를 기록(source=MANUAL)
 * - 원본을 덮어쓰지 않는 정정(source=CORRECTION)
 * - 하나의 기록을 4개 역할(학생 본인 / 학부모 자녀 / 기사 담당버스 / 관리자 테넌트)이 각자 범위에서 조회
 */
@Service
public class RideEventServiceImpl implements RideEventService {

    private final RideEventRepository rideEventRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final NotificationService notificationService;

    public RideEventServiceImpl(RideEventRepository rideEventRepository,
                                BusRepository busRepository,
                                StudentRepository studentRepository,
                                StudentGuardianRepository studentGuardianRepository,
                                NotificationService notificationService) {
        this.rideEventRepository = rideEventRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.notificationService = notificationService;
    }

    // ── 기록/정정 ──

    @Override
    @Transactional
    public RideEventResponse record(AuthUser driver, RecordRideRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);

        Student student = studentRepository.findById(req.studentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생을 찾을 수 없습니다"));
        Long busTenantId = bus.getTenant().getId();
        if (!student.getTenant().getId().equals(busTenantId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "학생과 버스의 학원이 다릅니다");
        }

        Long stopId = req.stopId() != null ? req.stopId()
                : (student.getBoardingStop() != null ? student.getBoardingStop().getId() : null);

        RideEvent saved = rideEventRepository.save(RideEvent.builder()
                .tenantId(busTenantId)
                .studentId(student.getId())
                .busId(bus.getId())
                .stopId(stopId)
                .type(req.type())
                .occurredAt(LocalDateTime.now())
                .lat(req.lat())
                .lng(req.lng())
                .source(RideSource.MANUAL)
                .build());
        notifyRideRecorded(saved, student.getName());
        return RideEventResponse.from(saved);
    }

    @Override
    @Transactional
    public RideEventResponse correct(AuthUser actor, Long eventId, CorrectionRequest req) {
        RideEvent original = rideEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "기록을 찾을 수 없습니다"));
        requireCorrectionPermission(actor, original);

        RideEvent correction = rideEventRepository.save(RideEvent.builder()
                .tenantId(original.getTenantId())
                .studentId(original.getStudentId())
                .busId(original.getBusId())
                .stopId(req.stopId() != null ? req.stopId() : original.getStopId())
                .type(req.type())
                .occurredAt(req.occurredAt() != null ? req.occurredAt() : original.getOccurredAt())
                .lat(req.lat())
                .lng(req.lng())
                .source(RideSource.CORRECTION)
                .correctedBy(actor.userId())
                .correctedAt(LocalDateTime.now())
                .originalRef(original.getId())
                .build());
        return RideEventResponse.from(correction);
    }

    // ── 역할별 조회 ──

    @Override
    @Transactional(readOnly = true)
    public List<RideEventResponse> getMyRecords(AuthUser student, LocalDate date) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        LocalDateTime[] range = dayRange(date);
        return toResponses(rideEventRepository
                .findByStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(me.getId(), range[0], range[1]));
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public List<RideEventResponse> getRosterRecords(AuthUser driver, Long busId, LocalDate date) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);
        LocalDateTime[] range = dayRange(date);
        return toResponses(rideEventRepository
                .findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(busId, range[0], range[1]));
    }

    @Override
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

    // ── 내부 헬퍼 ──

    /**
     * 승/하차 기록 완료 즉시(임계값 없이) 학부모에게 알림 — 문서 7장 표의 BOARD_DONE/ALIGHT_DONE.
     * dedupKey 에 정류장/버스를 "단계"로 포함해, 같은 학생의 승차·하차가 서로 다른 알림으로 남게 한다.
     */
    private void notifyRideRecorded(RideEvent event, String studentName) {
        NotificationType type = event.getType() == RideType.BOARD
                ? NotificationType.BOARD_DONE : NotificationType.ALIGHT_DONE;
        String stage = event.getStopId() != null ? "stop" + event.getStopId() : "bus" + event.getBusId();
        String dedupKey = NotificationService.dedupKey(
                type, event.getStudentId(), event.getOccurredAt().toLocalDate(), stage);
        String verb = event.getType() == RideType.BOARD ? "승차" : "하차";
        notificationService.notify(type, event.getTenantId(), event.getStudentId(),
                dedupKey, studentName + " 학생이 " + verb + "했습니다");
    }

    private void requireAssignedDriver(Bus bus, AuthUser driver) {
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 처리할 수 있습니다");
        }
    }

    private void requireCorrectionPermission(AuthUser actor, RideEvent original) {
        if (actor.isPlatformAdmin() || actor.belongsToTenant(original.getTenantId())) {
            return; // 학원 관리자·플랫폼 관리자
        }
        Bus bus = busRepository.findById(original.getBusId()).orElse(null);
        if (bus != null && bus.getDriver() != null && bus.getDriver().getId().equals(actor.userId())) {
            return; // 담당 기사
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private LocalDateTime[] dayRange(LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return new LocalDateTime[]{day.atStartOfDay(), day.plusDays(1).atStartOfDay()};
    }

    private List<RideEventResponse> toResponses(List<RideEvent> events) {
        return events.stream().map(RideEventResponse::from).toList();
    }
}
