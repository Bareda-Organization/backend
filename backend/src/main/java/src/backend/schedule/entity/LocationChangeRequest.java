package src.backend.schedule.entity;

import java.time.LocalDate;

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
import src.backend.routing.domain.RouteDirection;

/**
 * 학부모 등하원 위치 변경 요청(P2). 관리자 승인이 없고 접수 즉시 자동 판정되므로
 * 상태전이 메서드를 두지 않는다 — 판정이 끝난 뒤 <b>1회만</b> 저장한다.
 * <p>이 저장소에는 별도 감사 로그 테이블이 없다. 좌표를 바꾸지 않은 {@code REJECTED}·{@code BLOCKED}
 * 도 반드시 저장해야 "누가 언제 어느 학생의 위치를 어디로 바꾸려 했는지"가 남는다.
 * <p>⚠️ 컬럼명은 {@code V1__init_schema.sql} 의 {@code location_change_request} 와 정확히 일치해야 한다
 * ({@code ddl-auto: validate}).
 */
@Entity
@Table(name = "location_change_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationChangeRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private Long requestedBy;            // 신청한 학부모 User id

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteDirection direction;

    @Column(nullable = false)
    private LocalDate targetDate;

    @Column(nullable = false)
    private Double newLat;               // → new_lat

    @Column(nullable = false)
    private Double newLng;               // → new_lng

    private String newAddress;           // 요청 주소/정류장명 → new_address

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationChangeDecision decision;

    // ⚠️ 이름을 명시한다 — 기본 네이밍 전략은 끝의 한 글자 단위를 붙여 delta_distancem 으로 만든다.
    // RoutePlan.totalDistanceM 도 같은 이유로 name 을 명시하고 있다.
    @Column(name = "delta_distance_m")
    private Double deltaDistanceM;       // 계획 비교를 한 경우만(APPLIED/BLOCKED 는 null)

    @Column(name = "delta_duration_s")
    private Double deltaDurationS;

    private Long appliedPlanId;          // REPLANNED 일 때 새로 배포된 계획 id → applied_plan_id

    private String reason;               // 판정 사유 문구(응답·알림에 그대로 쓴다)

    @Builder
    public LocationChangeRequest(Long tenantId, Long studentId, Long requestedBy, RouteDirection direction,
                                 LocalDate targetDate, Double newLat, Double newLng, String newAddress,
                                 LocationChangeDecision decision, Double deltaDistanceM, Double deltaDurationS,
                                 Long appliedPlanId, String reason) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.requestedBy = requestedBy;
        this.direction = direction;
        this.targetDate = targetDate;
        this.newLat = newLat;
        this.newLng = newLng;
        this.newAddress = newAddress;
        this.decision = decision;
        this.deltaDistanceM = deltaDistanceM;
        this.deltaDurationS = deltaDurationS;
        this.appliedPlanId = appliedPlanId;
        this.reason = reason;
    }
}
