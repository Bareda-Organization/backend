package src.backend.routing.pipeline;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import src.backend.routing.engine.spec.OrderedStop;

/**
 * 노선 계산 파이프라인의 산출물 — {@code ARCHITECTURE §8.2} ①~④ 를 마친 상태다.
 *
 * <p><b>저장하지 않는다.</b> {@code confirmed_route}·{@code route_version}·{@code run_stop} 행을
 * 만드는 것은 확정 배치(Phase 7)의 일이고, 이 파이프라인은 계산해서 이 값을 돌려주는 데까지다 —
 * 온디맨드 미리보기는 확정 노선을 건드리지 않은 채 결과만 보여야 하므로(ARCHITECTURE §8.5),
 * 계산과 저장이 한 덩어리면 미리보기라는 개념 자체가 성립하지 않는다.
 *
 * @param stops               방문 순서대로의 정차지 — 승하차지와 경유 지점이 섞여 있다
 * @param estDurationMin      출발부터 도착지까지의 총 소요(분)
 * @param estDistanceKm       총 주행거리 — {@code route_version.est_distance_km numeric(6,2)}
 * @param etas                정차지별 도착 예정 시각. {@code stops} 와 같은 길이·같은 순서다
 * @param unresolvedStudentIds 좌표를 얻지 못해 명단에서 분리된 학생 (목표 2)
 * @param snapshot            산출 조건 4항 (TECH_DECISIONS §8.5.1)
 */
public record RouteComputation(
        List<OrderedStop> stops,
        int estDurationMin,
        BigDecimal estDistanceKm,
        List<OffsetDateTime> etas,
        List<Long> unresolvedStudentIds,
        ComputationSnapshot snapshot) {

    /**
     * 길이 일치를 여기서 막는 이유는 두 목록이 <b>자리로 대응</b>하기 때문이다 — 하나가 짧으면
     * 마지막 정차지의 도착 예정 시각이 조용히 사라지고, 그것을 읽는 쪽은 없는 자리를 묻지 않으므로
     * 관제 화면(O-05)에서 그 승하차지만 시각이 비는 형태로만 드러난다.
     *
     * <p>{@code unresolvedStudentIds} 가 비지 않아도 <b>계산은 성공</b>이다 — 한 명의 주소 오류가
     * 회차 전체를 무르면 버스 한 대가 선다.
     */
    public RouteComputation {
        stops = List.copyOf(Objects.requireNonNull(stops, "정차지 목록이 없다"));
        etas = List.copyOf(Objects.requireNonNull(etas, "도착 예정 시각 목록이 없다"));
        unresolvedStudentIds = List.copyOf(
                Objects.requireNonNull(unresolvedStudentIds, "좌표 미확보 학생 목록이 없다"));
        Objects.requireNonNull(estDistanceKm, "총 주행거리가 없다");
        Objects.requireNonNull(snapshot, "산출 조건 스냅샷이 없다");
        if (etas.size() != stops.size()) {
            throw new IllegalArgumentException(
                    "도착 예정 시각은 정차지와 같은 길이여야 한다: 정차지 " + stops.size() + " · 시각 " + etas.size());
        }
    }
}
