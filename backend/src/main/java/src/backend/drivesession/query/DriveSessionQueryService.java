package src.backend.drivesession.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.roster.ActiveRosterReader;
import src.backend.bus.access.BusCrewGuard;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.DriveSessionRosterEntry;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.routing.domain.RouteDirection;
import src.backend.student.entity.Student;

/**
 * 운행 세션 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.drivesession.command.DriveSessionCommandService}를 호출하지 않는다.
 */
@Service
public class DriveSessionQueryService {

    private final DriveSessionRepository driveSessionRepository;
    private final BusRepository busRepository;
    private final ActiveRosterReader activeRosterReader;

    public DriveSessionQueryService(DriveSessionRepository driveSessionRepository,
                                    BusRepository busRepository,
                                    ActiveRosterReader activeRosterReader) {
        this.driveSessionRepository = driveSessionRepository;
        this.busRepository = busRepository;
        this.activeRosterReader = activeRosterReader;
    }

    /** 기사·선탑자: 담당 버스 운행 이력(전체). 선탑자 앱은 여기서 진행 중인 세션 id 를 고른다(§4.1 사슬). */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getBusHistory(AuthUser actor, Long busId) {
        return getBusHistory(actor, busId, null);
    }

    /**
     * 기사·선탑자: 담당 버스 운행 이력. BG-7 — {@code status} 가 있으면 그 상태만 골라 응답 크기를 줄인다
     * (주로 진행 중 세션 조회용). {@code null} 이면 위 전체 이력 오버로드와 동일하게 동작해 하위 호환을 지킨다.
     */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getBusHistory(AuthUser actor, Long busId, DriveSessionStatus status) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        BusCrewGuard.requireAssignedCrew(bus, actor);
        List<DriveSession> sessions = status == null
                ? driveSessionRepository.findByBusIdOrderByStartedAtDesc(busId)
                : driveSessionRepository.findByBusIdAndStatusOrderByStartedAtDesc(busId, status);
        return sessions.stream().map(DriveSessionResponse::from).toList();
    }

    /** 관리자: 학원 운행 이력(법정 운행기록 열람, 전체). */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getTenantHistory(AuthUser admin, Long tenantId) {
        return getTenantHistory(admin, tenantId, null);
    }

    /**
     * 관리자: 학원 운행 이력. BG-7 — {@code status} 가 있으면 그 상태만 골라 응답 크기를 줄인다
     * (관제 화면이 진행 중인 세션만 폴링할 때). {@code null} 이면 전체 이력 오버로드와 동일하다.
     */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getTenantHistory(AuthUser admin, Long tenantId, DriveSessionStatus status) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<DriveSession> sessions = status == null
                ? driveSessionRepository.findByTenantIdOrderByStartedAtDesc(effectiveTenant)
                : driveSessionRepository.findByTenantIdAndStatusOrderByStartedAtDesc(effectiveTenant, status);
        return sessions.stream().map(DriveSessionResponse::from).toList();
    }

    /** 기사·선탑자: 이 운행 세션의 당일 명단(rideevent·attendance 명단 연계, 결석 자동 제외). */
    @Transactional(readOnly = true)
    public List<DriveSessionRosterEntry> getRoster(AuthUser actor, Long sessionId) {
        DriveSession session = driveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "운행 세션을 찾을 수 없습니다"));
        if (!session.getDriverId().equals(actor.userId())) {
            Bus bus = busRepository.findById(session.getBusId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
            if (bus.getAttendant() == null || !bus.getAttendant().getId().equals(actor.userId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 시작한 운행 또는 담당 선탑자만 조회할 수 있습니다");
            }
        }
        List<Student> roster = activeRosterReader.forBus(session.getBusId(), session.getServiceDate());
        return roster.stream().map(student -> toRosterEntry(student, session.getDirection())).toList();
    }

    private DriveSessionRosterEntry toRosterEntry(Student student, RouteDirection direction) {
        if (direction == RouteDirection.PICKUP) {
            // D-K: 학생 전용 pickup 좌표가 있으면 우선, 없으면 공유 boardingStop 으로 폴백
            if (student.getPickupLat() != null && student.getPickupLng() != null) {
                return new DriveSessionRosterEntry(student.getId(), student.getName(), student.getPhotoUrl(),
                        student.getPickupAddress(), student.getPickupLat(), student.getPickupLng());
            }
            var stop = student.getBoardingStop();
            return new DriveSessionRosterEntry(student.getId(), student.getName(), student.getPhotoUrl(),
                    stop != null ? stop.getName() : null,
                    stop != null ? stop.getLat() : null, stop != null ? stop.getLng() : null);
        }
        return new DriveSessionRosterEntry(student.getId(), student.getName(), student.getPhotoUrl(),
                student.getDropoffAddress(), student.getDropoffLat(), student.getDropoffLng());
    }
}
