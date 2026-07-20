package src.backend.drivesession.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.RouteDirection;

/**
 * 기사 1회 운행(등원 또는 하원 1건)의 시작~종료 기록 — 그 자체가 "일일 운행 로그"다(Phase 7,
 * 별도 로그 엔티티를 두지 않는다). 시작 시각은 이후 도착예상시각(ETA) 계산의 기준점이 되므로
 * §11.4 Phase 6f에서 보류했던 NO_SHOW/APPROACH 알림이 이 모듈 이후 이 시각을 기준점으로 쓸 수 있다.
 */
@Entity
@Table(name = "drive_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriveSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long busId;

    @Column(nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteDirection direction;

    @Column(nullable = false)
    private LocalDate serviceDate;

    private Long routePlanId;   // 배포된 RoutePlan 연결(있으면). 계획 없이도 운행 시작은 허용한다.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriveSessionStatus status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @Builder
    public DriveSession(Long tenantId, Long busId, Long driverId, RouteDirection direction,
                        LocalDate serviceDate, Long routePlanId) {
        this.tenantId = tenantId;
        this.busId = busId;
        this.driverId = driverId;
        this.direction = direction;
        this.serviceDate = serviceDate;
        this.routePlanId = routePlanId;
        this.status = DriveSessionStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    /** 운행 종료(IN_PROGRESS → COMPLETED). 이미 종료된 세션을 다시 종료할 수 없다. */
    public void end() {
        if (status != DriveSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 종료된 운행입니다");
        }
        this.status = DriveSessionStatus.COMPLETED;
        this.endedAt = LocalDateTime.now();
    }
}
