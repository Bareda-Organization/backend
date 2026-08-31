package src.backend.routing.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.enums.ChangeType;

/**
 * 회차 노선의 정차 항목 — 순번·변경 구분·도착 시각은 배포 버전마다 달라지는 값이라 승하차지
 * 마스터에 보관할 수 없다(ERD §3.3 · C-05 · C-12 · RTE-05·10 · RUN-04 · RST-01 · O-05).
 *
 * <p>{@code stop_id}(학생 승하차지) · {@code waypoint_id}(강제 경유지)는 DB CHECK
 * ({@code ck_run_stop_target_exclusive})로 정확히 하나만 NOT NULL 이 강제된다 — 둘 다 nullable
 * {@code Long} 으로 매핑하고, 배타 강제 자체는 이 태스크에서 코드로 옮기지 않는다.
 *
 * <p>{@code created_at}·{@code updated_at} 컬럼이 없어 {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Entity
@Table(name = "run_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "waypoint_id")
    private Long waypointId;

    @Column(name = "seq", nullable = false)
    private int seq;

    /** {@code ck_run_stop_change} 는 {@code added}·{@code skipped} 부분집합만 허용한다({@link ChangeType} 은 상위 집합). */
    @Convert(converter = ChangeType.Db.class)
    @Column(name = "change", length = 10)
    private ChangeType change;

    @Column(name = "skip_notice", length = 200)
    private String skipNotice;

    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;

    /** 승하차지별 도착 예정 시각. 관제 전용이며 학부모·학생 응답에는 포함되지 않는다. */
    @Column(name = "eta")
    private OffsetDateTime eta;

    private RunStop(Long routeVersionId, Long stopId, Long waypointId, int seq, OffsetDateTime eta) {
        this.routeVersionId = routeVersionId;
        this.stopId = stopId;
        this.waypointId = waypointId;
        this.seq = seq;
        this.eta = eta;
    }

    /**
     * 배포 버전에 학생 승하차지를 정차 항목으로 배정할 때 생성한다(RTE-05).
     *
     * <p>{@code eta} 는 확정 배치(Phase 7)가 노선 계산 ④단계 산출물을 그대로 넘긴다 — 관제(O-05)
     * 가 읽는 값이라 처음부터 채워 넣지 않으면 첫 확정 노선의 정차지마다 시각이 비게 된다.
     */
    public static RunStop forStop(Long routeVersionId, Long stopId, int seq, OffsetDateTime eta) {
        return new RunStop(routeVersionId, stopId, null, seq, eta);
    }

    /** 배포 버전에 강제 경유지를 정차 항목으로 배정할 때 생성한다(RTE-10) — {@code eta} 는 {@link #forStop} 과 같다. */
    public static RunStop forWaypoint(Long routeVersionId, Long waypointId, int seq, OffsetDateTime eta) {
        return new RunStop(routeVersionId, null, waypointId, seq, eta);
    }

    /**
     * ③구간 미등원 토글로 그 승하차지에 남은 탑승자가 0명이 되면 경유하되 정차하지 않음으로
     * 표시한다(API_SPEC §3.6 ③ · C-05). {@code seq} 는 건드리지 않는다 — 이 구간은 재최적화가 없어
     * 나머지 정차 순번이 그대로 유지돼야 한다.
     */
    public void markSkipped(String skipNotice) {
        this.change = ChangeType.SKIPPED;
        this.skipNotice = skipNotice;
    }

    /** 기사의 도착 처리로 도착 시각을 기록한다(API_SPEC §4.5, RUN-04). 재처리는 호출부가 막는다. */
    public void markArrived(OffsetDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }
}
