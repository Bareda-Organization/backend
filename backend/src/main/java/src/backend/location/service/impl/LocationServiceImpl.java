package src.backend.location.service.impl;

import src.backend.location.service.spec.LocationService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationPing;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.dto.LocationView;
import src.backend.location.repository.spec.LocationRepository;
import src.backend.location.websocket.LocationSessionRegistry;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.service.spec.NotificationService;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * {@link LocationService} 기본 구현.
 * 저장은 실시간·휘발성이라 {@link LocationRepository}(in-memory)에 학생별 최신 1건으로 두고,
 * 조회 권한은 승하차 기록과 동일한 4-계층(학생 본인 / 학부모 자녀 / 기사 담당버스 / 관리자 테넌트)으로 나눈다.
 */
@Service
public class LocationServiceImpl implements LocationService {

    private final LocationRepository locationRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final BusRepository busRepository;
    private final LocationSessionRegistry sessionRegistry;
    private final NotificationService notificationService;
    private final Duration lossGrace;

    public LocationServiceImpl(LocationRepository locationRepository,
                               StudentRepository studentRepository,
                               StudentGuardianRepository studentGuardianRepository,
                               BusRepository busRepository,
                               LocationSessionRegistry sessionRegistry,
                               NotificationService notificationService,
                               @Value("${app.connection.loss-grace-seconds:30}") long lossGraceSeconds) {
        this.locationRepository = locationRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.busRepository = busRepository;
        this.sessionRegistry = sessionRegistry;
        this.notificationService = notificationService;
        this.lossGrace = Duration.ofSeconds(lossGraceSeconds);
    }

    // ── 입력(ingest) ──

    @Override
    @Transactional(readOnly = true)
    public void reportSelf(AuthUser student, LocationReportRequest req) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        // 트랜잭션 안에서 lazy 한 tenant 를 안전하게 읽어 좌표 출처(GPS)로 저장 경로에 합류시킨다.
        ingest(me.getTenant().getId(), me.getId(), req.lat(), req.lng(), LocationOrigin.GPS);
    }

    @Override
    public void ingest(Long tenantId, Long studentId, double lat, double lng, LocationOrigin origin) {
        locationRepository.save(new LocationPing(studentId, tenantId, lat, lng, LocalDateTime.now(), origin));
    }

    // ── 역할별 조회 ──

    @Override
    @Transactional(readOnly = true)
    public LocationView getMyLocation(AuthUser student) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        LocationPing ping = locationRepository.findLatest(me.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "아직 위치 정보가 없습니다"));
        return LocationView.of(me.getName(), ping);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationView> getChildrenLocations(AuthUser parent) {
        List<Student> children = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .toList();
        return viewsFor(children);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationView> getBusLocations(AuthUser driver, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 조회할 수 있습니다");
        }
        return viewsFor(studentRepository.findByAssignedBusId(busId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationView> getTenantLocations(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return viewsFor(studentRepository.findByTenantId(effectiveTenant));
    }

    @Override
    @Transactional
    public void checkOverdueDisconnections() {
        LocalDateTime cutoff = LocalDateTime.now().minus(lossGrace);
        for (Map.Entry<Long, LocalDateTime> e : sessionRegistry.snapshotDisconnected().entrySet()) {
            Long studentId = e.getKey();
            LocalDateTime disconnectedAt = e.getValue();
            if (disconnectedAt.isAfter(cutoff)) {
                continue; // 아직 유예시간 이내 — 오탐 방지(터널·순간 네트워크 끊김 등)
            }
            studentRepository.findById(studentId).ifPresent(student -> {
                String dedupKey = NotificationService.dedupKey(
                        NotificationType.CONNECTION_LOST, studentId, disconnectedAt.toLocalDate(),
                        "disconnect:" + disconnectedAt);
                notificationService.notify(NotificationType.CONNECTION_LOST, student.getTenant().getId(), studentId,
                        dedupKey, student.getName() + " 학생의 위치 연결이 끊겼습니다");
            });
        }
    }

    /** 학생 목록 → 최신 좌표가 있는 학생만 뷰로 변환(아직 좌표 없는 학생은 제외). */
    private List<LocationView> viewsFor(List<Student> students) {
        return students.stream()
                .flatMap(s -> locationRepository.findLatest(s.getId()).stream()
                        .map(ping -> LocationView.of(s.getName(), ping)))
                .toList();
    }
}
