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

    /**
     * P2 위치 변경 차단 판정(I-4) — 상태를 보지 않는 존재 확인.
     * 위 {@code findBy...AndStatus}는 "진행 중"만 찾지만 I-4 는 <b>종료된 세션도</b> 거부 대상이라 따로 필요하다.
     */
    boolean existsByBusIdAndDirectionAndServiceDate(Long busId, RouteDirection direction, LocalDate serviceDate);

    List<DriveSession> findByBusIdOrderByStartedAtDesc(Long busId);

    List<DriveSession> findByTenantIdOrderByStartedAtDesc(Long tenantId);

    /** BG-7 — 담당 버스 이력 중 특정 상태(주로 IN_PROGRESS)만 골라 응답 크기를 줄인다. */
    List<DriveSession> findByBusIdAndStatusOrderByStartedAtDesc(Long busId, DriveSessionStatus status);

    /** BG-7 — 학원 이력 중 특정 상태만. 관제 화면이 진행 중인 세션만 폴링할 때 쓴다. */
    List<DriveSession> findByTenantIdAndStatusOrderByStartedAtDesc(Long tenantId, DriveSessionStatus status);

    /** APPROACH/NO_SHOW 스케줄러(G1) — 판정 대상인 진행 중 등원 세션만 훑는다. */
    List<DriveSession> findByStatusAndDirection(DriveSessionStatus status, RouteDirection direction);
}
