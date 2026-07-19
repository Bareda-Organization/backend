package src.backend.routing.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;

/**
 * 버스 1대·방향(등원/하원) 1건의 자동 추천 노선 계획.
 * 상태전이: DRAFT(최초 생성, Phase 6d) → RECOMMENDED(당일 재계산, Phase 6e) → APPROVED → PUBLISHED(Phase 6f).
 * 재계산은 기존 행을 고치지 않고 {@code version} 을 올린 새 행을 만든다(이력 보존).
 */
@Entity
@Table(name = "route_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutePlan extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long busId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoutePlanStatus status;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Lob
    private String polyline;

    @Column(nullable = false)
    private double totalDistanceM;

    @Column(nullable = false)
    private double totalDurationS;

    @OneToMany(mappedBy = "routePlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<RoutePlanStop> stops = new ArrayList<>();

    private Long approvedBy;
    private Long publishedBy;

    @Builder
    public RoutePlan(Long tenantId, Long busId, RouteDirection direction, int version, LocalDate serviceDate,
                     String polyline, double totalDistanceM, double totalDurationS) {
        this.tenantId = tenantId;
        this.busId = busId;
        this.direction = direction;
        this.status = RoutePlanStatus.DRAFT;
        this.version = version;
        this.serviceDate = serviceDate;
        this.polyline = polyline;
        this.totalDistanceM = totalDistanceM;
        this.totalDurationS = totalDurationS;
    }

    /** 정차 순서 1건 추가 — seq 는 호출 순서대로 1부터 자동 부여. */
    public void addStop(Long studentId, double lat, double lng, long etaSeconds) {
        stops.add(new RoutePlanStop(this, stops.size() + 1, studentId, lat, lng, etaSeconds));
    }

    /** 관리자 승인(DRAFT/RECOMMENDED → APPROVED). 승인/배포 API 는 Phase 6f 몫. */
    public void approve(Long adminUserId) {
        if (status != RoutePlanStatus.DRAFT && status != RoutePlanStatus.RECOMMENDED) {
            throw new BusinessException(ErrorCode.CONFLICT, "초안·추천 상태에서만 승인할 수 있습니다");
        }
        this.status = RoutePlanStatus.APPROVED;
        this.approvedBy = adminUserId;
    }

    /** 관리자 배포(APPROVED → PUBLISHED). */
    public void publish(Long adminUserId) {
        if (status != RoutePlanStatus.APPROVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "승인된 계획만 배포할 수 있습니다");
        }
        this.status = RoutePlanStatus.PUBLISHED;
        this.publishedBy = adminUserId;
    }
}
