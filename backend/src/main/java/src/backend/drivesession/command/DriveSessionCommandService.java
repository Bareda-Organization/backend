package src.backend.drivesession.command;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.StartDriveSessionRequest;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;

/**
 * 운행 세션 시작/종료 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 */
@Service
public class DriveSessionCommandService {

    private final DriveSessionRepository driveSessionRepository;
    private final BusRepository busRepository;
    private final RoutePlanRepository routePlanRepository;

    public DriveSessionCommandService(DriveSessionRepository driveSessionRepository,
                                      BusRepository busRepository,
                                      RoutePlanRepository routePlanRepository) {
        this.driveSessionRepository = driveSessionRepository;
        this.busRepository = busRepository;
        this.routePlanRepository = routePlanRepository;
    }

    /** 기사: 운행 시작. 배포된 계획이 있으면 자동 연결하되, 없어도 시작은 허용한다(계획 없는 수동 운행). */
    @Transactional
    public DriveSessionResponse start(AuthUser driver, StartDriveSessionRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);

        LocalDate serviceDate = req.serviceDate() != null ? req.serviceDate() : LocalDate.now();
        driveSessionRepository
                .findByBusIdAndDirectionAndServiceDateAndStatus(
                        bus.getId(), req.direction(), serviceDate, DriveSessionStatus.IN_PROGRESS)
                .ifPresent(s -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "이미 진행 중인 운행이 있습니다");
                });

        Long routePlanId = routePlanRepository
                .findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(bus.getId(), serviceDate, RoutePlanStatus.PUBLISHED)
                .stream()
                .filter(plan -> plan.getDirection() == req.direction())
                .findFirst()
                .map(RoutePlan::getId)
                .orElse(null);

        DriveSession saved = driveSessionRepository.save(DriveSession.builder()
                .tenantId(bus.getTenant().getId())
                .busId(bus.getId())
                .driverId(driver.userId())
                .direction(req.direction())
                .serviceDate(serviceDate)
                .routePlanId(routePlanId)
                .build());
        return DriveSessionResponse.from(saved);
    }

    /** 기사: 운행 종료(IN_PROGRESS → COMPLETED). */
    @Transactional
    public DriveSessionResponse end(AuthUser driver, Long id) {
        DriveSession session = driveSessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "운행 세션을 찾을 수 없습니다"));
        requireOwner(session, driver);
        session.end();
        return DriveSessionResponse.from(session);
    }

    private void requireAssignedDriver(Bus bus, AuthUser driver) {
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 처리할 수 있습니다");
        }
    }

    private void requireOwner(DriveSession session, AuthUser driver) {
        if (!session.getDriverId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 시작한 운행만 종료할 수 있습니다");
        }
    }
}
