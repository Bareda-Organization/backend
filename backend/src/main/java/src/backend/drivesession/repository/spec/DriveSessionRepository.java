package src.backend.drivesession.repository.spec;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.routing.domain.RouteDirection;

public interface DriveSessionRepository extends JpaRepository<DriveSession, Long> {

    /** 중복 시작 방지 — 같은 버스·방향·날짜에 이미 진행 중인 세션이 있는지 확인. */
    Optional<DriveSession> findByBusIdAndDirectionAndServiceDateAndStatus(
            Long busId, RouteDirection direction, LocalDate serviceDate, DriveSessionStatus status);

    List<DriveSession> findByBusIdOrderByStartedAtDesc(Long busId);

    List<DriveSession> findByTenantIdOrderByStartedAtDesc(Long tenantId);
}
