package src.backend.drivesession.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.query.AttendanceQueryService;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.DriveSessionRosterEntry;
import src.backend.drivesession.entity.DriveSession;
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
    private final AttendanceQueryService attendanceQueryService;

    public DriveSessionQueryService(DriveSessionRepository driveSessionRepository,
                                    BusRepository busRepository,
                                    AttendanceQueryService attendanceQueryService) {
        this.driveSessionRepository = driveSessionRepository;
        this.busRepository = busRepository;
        this.attendanceQueryService = attendanceQueryService;
    }

    /** 기사: 담당 버스 운행 이력. */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getBusHistory(AuthUser driver, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);
        return driveSessionRepository.findByBusIdOrderByStartedAtDesc(busId).stream()
                .map(DriveSessionResponse::from).toList();
    }

    /** 관리자: 학원 운행 이력(법정 운행기록 열람). */
    @Transactional(readOnly = true)
    public List<DriveSessionResponse> getTenantHistory(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return driveSessionRepository.findByTenantIdOrderByStartedAtDesc(effectiveTenant).stream()
                .map(DriveSessionResponse::from).toList();
    }

    /** 기사: 이 운행 세션의 당일 명단(rideevent·attendance 명단 연계, 결석 자동 제외). */
    @Transactional(readOnly = true)
    public List<DriveSessionRosterEntry> getRoster(AuthUser driver, Long sessionId) {
        DriveSession session = driveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "운행 세션을 찾을 수 없습니다"));
        if (!session.getDriverId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 시작한 운행만 조회할 수 있습니다");
        }
        List<Student> roster = attendanceQueryService.getActiveRoster(session.getBusId(), session.getServiceDate());
        return roster.stream().map(student -> toRosterEntry(student, session.getDirection())).toList();
    }

    private DriveSessionRosterEntry toRosterEntry(Student student, RouteDirection direction) {
        if (direction == RouteDirection.PICKUP) {
            var stop = student.getBoardingStop();
            return new DriveSessionRosterEntry(student.getId(), student.getName(),
                    stop != null ? stop.getName() : null,
                    stop != null ? stop.getLat() : null,
                    stop != null ? stop.getLng() : null);
        }
        return new DriveSessionRosterEntry(student.getId(), student.getName(),
                student.getDropoffAddress(), student.getDropoffLat(), student.getDropoffLng());
    }

    private void requireAssignedDriver(Bus bus, AuthUser driver) {
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 조회할 수 있습니다");
        }
    }
}
