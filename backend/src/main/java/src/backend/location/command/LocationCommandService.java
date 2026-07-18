package src.backend.location.command;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationPing;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.event.StudentConnectionLostEvent;
import src.backend.location.repository.spec.LocationRepository;
import src.backend.location.websocket.LocationSessionRegistry;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 실시간 위치 입력(보고/주입) + 연결 끊김 판정 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * 끊김 확정 시 직접 알림 호출 대신 {@link StudentConnectionLostEvent}를 발행해(AFTER_COMMIT → Kafka)
 * 알림 모듈과의 직접 결합을 없앤다. {@code ingest()} 로 좌표를 저장하는 경로 자체는 Phase 3(실시간 push)
 * 범위라 이 단계에서는 이벤트를 발행하지 않는다.
 */
@Service
public class LocationCommandService {

    private final LocationRepository locationRepository;
    private final StudentRepository studentRepository;
    private final LocationSessionRegistry sessionRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration lossGrace;

    public LocationCommandService(LocationRepository locationRepository,
                                  StudentRepository studentRepository,
                                  LocationSessionRegistry sessionRegistry,
                                  ApplicationEventPublisher eventPublisher,
                                  @Value("${app.connection.loss-grace-seconds:30}") long lossGraceSeconds) {
        this.locationRepository = locationRepository;
        this.studentRepository = studentRepository;
        this.sessionRegistry = sessionRegistry;
        this.eventPublisher = eventPublisher;
        this.lossGrace = Duration.ofSeconds(lossGraceSeconds);
    }

    @Transactional
    public void reportSelf(AuthUser student, LocationReportRequest req) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        // 트랜잭션 안에서 lazy 한 tenant 를 안전하게 읽어 좌표 출처(GPS)로 저장 경로에 합류시킨다.
        ingest(me.getTenant().getId(), me.getId(), req.lat(), req.lng(), LocationOrigin.GPS);
    }

    public void ingest(Long tenantId, Long studentId, double lat, double lng, LocationOrigin origin) {
        locationRepository.save(new LocationPing(studentId, tenantId, lat, lng, LocalDateTime.now(), origin));
    }

    @Transactional
    public void checkOverdueDisconnections() {
        LocalDateTime cutoff = LocalDateTime.now().minus(lossGrace);
        for (Map.Entry<Long, LocalDateTime> e : sessionRegistry.snapshotDisconnected().entrySet()) {
            Long studentId = e.getKey();
            LocalDateTime disconnectedAt = e.getValue();
            if (disconnectedAt.isAfter(cutoff)) {
                continue; // 아직 유예시간 이내 — 오탐 방지(터널·순간 네트워크 끊김 등)
            }
            studentRepository.findById(studentId).ifPresent(student ->
                    eventPublisher.publishEvent(StudentConnectionLostEvent.of(
                            student.getTenant().getId(), studentId, student.getName(), disconnectedAt)));
        }
    }
}
