package src.backend.rideevent.command;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.dto.RideEventResponse;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.event.RideCompletedEvent;
import src.backend.rideevent.event.StudentBoardedEvent;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 승하차 기록/정정 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * 기록 완료 시 직접 알림 호출 대신 {@link StudentBoardedEvent}/{@link RideCompletedEvent}를
 * 발행해(AFTER_COMMIT → Kafka) 알림 모듈과의 직접 결합을 없앤다.
 */
@Service
public class RideEventCommandService {

    private final RideEventRepository rideEventRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RideEventCommandService(RideEventRepository rideEventRepository,
                                   BusRepository busRepository,
                                   StudentRepository studentRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.rideEventRepository = rideEventRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.eventPublisher = eventPublisher;
    }

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

        if (saved.getType() == RideType.BOARD) {
            eventPublisher.publishEvent(StudentBoardedEvent.of(saved, student.getName()));
        } else {
            eventPublisher.publishEvent(RideCompletedEvent.of(saved, student.getName()));
        }
        return RideEventResponse.from(saved);
    }

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
}
