package src.backend.routing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고정 노선의 정차 순서 — 고정 노선이 "편성·최적화" 대상이라 순서를 가진 목록이 필요하고, 확정 노선의
 * 정차 목록({@code run_stop})과 수명 주기가 달라 같은 테이블에 수용할 수 없다(ERD §3.3 · RTE-01 ·
 * A-08 · P-08).
 *
 * <p>{@code created_at}·{@code updated_at} 컬럼이 없어 {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Entity
@Table(name = "route_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "stop_id", nullable = false)
    private Long stopId;

    @Column(name = "seq", nullable = false)
    private int seq;

    private RouteStop(Long routeId, Long stopId, int seq) {
        this.routeId = routeId;
        this.stopId = stopId;
        this.seq = seq;
    }

    /** 고정 노선에 정차 순번을 배정할 때 생성한다(RTE-01). */
    public static RouteStop forRoute(Long routeId, Long stopId, int seq) {
        return new RouteStop(routeId, stopId, seq);
    }
}
