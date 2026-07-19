package src.backend.routing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** RoutePlan 의 정차 순서 1건 — 부모 RoutePlan 이 생성·삭제를 전적으로 관리하는 종속 엔티티. */
@Entity
@Table(name = "route_plan_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutePlanStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_plan_id")
    private RoutePlan routePlan;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(nullable = false)
    private long etaSeconds;   // 노선 출발 기준 누적 소요시간(초)

    RoutePlanStop(RoutePlan routePlan, int seq, Long studentId, double lat, double lng, long etaSeconds) {
        this.routePlan = routePlan;
        this.seq = seq;
        this.studentId = studentId;
        this.lat = lat;
        this.lng = lng;
        this.etaSeconds = etaSeconds;
    }
}
