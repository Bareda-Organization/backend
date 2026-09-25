package src.backend.routing.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
import src.backend.global.common.enums.Direction;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.pipeline.RouteComputation;

/**
 * 회차 노선의 정차 항목 — 순번·변경 구분·도착 시각은 배포 버전마다 달라지는 값이라 승하차지
 * 마스터에 보관할 수 없다(ERD §3.3 · C-05 · C-12 · RTE-05·10 · RUN-04 · RST-01 · O-05).
 *
 * <p>항목은 {@code stop_id}(학생 승하차지) · {@code waypoint_id}(강제 경유지) · {@code destination}
 * (등원 회차의 도착지 = 학원, Ruling 327) 중 정확히 하나다 — DB CHECK({@code ck_run_stop_target_exclusive})
 * 가 강제하고, 배타 강제 자체는 코드로 옮기지 않는다.
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

    /**
     * 도착 처리된 정차지에서 버스가 100m 밖으로 벗어난 최초 시점(Ruling 307) — {@code null} 이면 아직
     * 출발하지 않은 것이다. 되돌리기 제한(BRD-05, Ruling 305)의 유일한 판정 기준이며, 뒤 순번 정차지
     * 참조에 기대던 파생 규칙(마지막 정차지에 걸리지 않는 결함)을 대체한다.
     *
     * <p>엔티티에 세터를 두지 않는다. {@link src.backend.routing.repository.RunStopRepository
     * #claimDeparture} 조건부 UPDATE 만이 이 값을 채운다({@link #proximityNotifiedAt} 과 같은 근거 —
     * "먼저 읽고 나중에 쓰면" 두 스케줄러 인스턴스가 같은 정차지를 동시에 미출발로 보고 둘 다 표시한다).
     */
    @Column(name = "departed_at")
    private OffsetDateTime departedAt;

    /** 승하차지별 도착 예정 시각. 관제 전용이며 학부모·학생 응답에는 포함되지 않는다. */
    @Column(name = "eta")
    private OffsetDateTime eta;

    /**
     * 근접 알림(NTF-04) 최초 1회 발송을 표시하는 시각 — {@code null} 이면 아직 발송하지 않은 것이다.
     * 엔티티에 세터를 두지 않는다. {@link src.backend.routing.repository.RunStopRepository
     * #claimProximityNotice} 조건부 UPDATE 만이 이 값을 채운다({@link #markArrived} 와 달리 "먼저 읽고
     * 나중에 쓰면" 두 스케줄러 인스턴스가 같은 정차 항목을 동시에 미도착으로 보고 둘 다 발송한다).
     */
    @Column(name = "proximity_notified_at")
    private OffsetDateTime proximityNotifiedAt;

    /**
     * 등원 회차의 도착지(학원) 항목이면 {@code true} — 마지막 순번 1행뿐이고 {@code stop_id}·
     * {@code waypoint_id} 가 둘 다 비어 있다. 이 항목의 도착 처리가 등원 운행 종료다(C-15, Ruling 327).
     */
    @Column(name = "destination", nullable = false)
    private boolean destination;

    private RunStop(Long routeVersionId, Long stopId, Long waypointId, int seq, OffsetDateTime eta,
            boolean destination) {
        this.routeVersionId = routeVersionId;
        this.stopId = stopId;
        this.waypointId = waypointId;
        this.seq = seq;
        this.eta = eta;
        this.destination = destination;
    }

    /**
     * 배포 버전에 학생 승하차지를 정차 항목으로 배정할 때 생성한다(RTE-05).
     *
     * <p>{@code eta} 는 확정 배치(Phase 7)가 노선 계산 ④단계 산출물을 그대로 넘긴다 — 관제(O-05)
     * 가 읽는 값이라 처음부터 채워 넣지 않으면 첫 확정 노선의 정차지마다 시각이 비게 된다.
     */
    public static RunStop forStop(Long routeVersionId, Long stopId, int seq, OffsetDateTime eta) {
        return new RunStop(routeVersionId, stopId, null, seq, eta, false);
    }

    /** 배포 버전에 강제 경유지를 정차 항목으로 배정할 때 생성한다(RTE-10) — {@code eta} 는 {@link #forStop} 과 같다. */
    public static RunStop forWaypoint(Long routeVersionId, Long waypointId, int seq, OffsetDateTime eta) {
        return new RunStop(routeVersionId, null, waypointId, seq, eta, false);
    }

    /** 등원 회차의 도착지(학원) 항목 — 정차 목록의 마지막 순번에 1개만 둔다(Ruling 327). */
    public static RunStop forDestination(Long routeVersionId, int seq) {
        return new RunStop(routeVersionId, null, null, seq, null, true);
    }

    /**
     * 노선 계산 결과 한 벌을 정차 항목으로 옮긴다 — 확정 배치 · 승인 재최적화 · 경유 지점 배포가 새 버전을
     * 쌓을 때 함께 쓴다. <b>등원이면 맨 뒤에 도착지(학원) 항목을 붙인다</b>(Ruling 327) — 세 곳 중 하나라도
     * 빠뜨리면 그 버전부터 등원 운행을 끝낼 항목이 사라진다.
     *
     * <p>도착지 항목의 {@code eta} 는 비운다 — 계산 결과가 도착지 시각을 따로 내지 않고, 관제는
     * {@code depart_time + est_duration_min} 으로 도착 예정을 낸다(Ruling 232).
     */
    public static List<RunStop> listOf(Long routeVersionId, RouteComputation computation, Direction direction) {
        List<OrderedStop> stops = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<RunStop> runStops = new ArrayList<>(stops.size() + 1);
        for (int i = 0; i < stops.size(); i++) {
            OrderedStop stop = stops.get(i);
            runStops.add(stop.stopId() != null
                    ? forStop(routeVersionId, stop.stopId(), stop.seq(), etas.get(i))
                    : forWaypoint(routeVersionId, stop.waypointId(), stop.seq(), etas.get(i)));
        }
        if (direction == Direction.TO_ACADEMY) {
            int lastSeq = stops.stream().mapToInt(OrderedStop::seq).max().orElse(0);
            runStops.add(forDestination(routeVersionId, lastSeq + 1));
        }
        return runStops;
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
