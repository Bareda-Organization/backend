package src.backend.routing.pipeline;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.FixedStop;

/**
 * 노선 계산 1회의 입력 — 회차 1건(버스 1대 · 방향 1개)이 도는 구간 전체다.
 *
 * <p>{@code origin}·{@code destination} 을 <b>호출자가 준다.</b> {@code route}·{@code academy}·
 * {@code run} 어디에도 좌표 컬럼이 부재해 서버가 읽어 올 자리가 없고, 정차지 중 하나를 골라
 * 기준점으로 쓰면 그 고름이 요청·결과 어디에도 안 남는 <b>보이지 않는 정책</b>이 된다 — 어느 자리를
 * 골랐는지에 따라 산출 순서가 달라지는데 사후에 설명할 수단이 부재해진다.
 *
 * @param roster       대상 명단 — ①단계가 이것을 좌표로 바꾼다
 * @param origin       출발지. 등원은 첫 승차지 이전 기준점, 하원은 학원
 * @param destination  도착지. 등원은 학원, 하원은 마지막 하차지 이후 기준점
 * @param fixedStops   순번이 고정된 경유 지점(RTE-10) — 최적화가 이 자리를 뒤집지 않는다
 * @param departAt     출발 시각 — 정차지별 도착 예정 시각이 이 시각에서 누적된다
 * @param policy       지도 API 타임아웃 · 호출자 구분 · 촉발 원인
 */
public record RouteComputationInput(
        DailyRoster roster,
        GeoPoint origin,
        GeoPoint destination,
        List<FixedStop> fixedStops,
        OffsetDateTime departAt,
        ComputationPolicy policy) {

    public RouteComputationInput {
        Objects.requireNonNull(roster, "대상 명단이 없다");
        Objects.requireNonNull(origin, "출발지가 없다");
        Objects.requireNonNull(destination, "도착지가 없다");
        Objects.requireNonNull(departAt, "출발 시각이 없으면 도착 예정 시각을 누적할 기준이 부재하다");
        Objects.requireNonNull(policy, "실행 정책이 없다");
        fixedStops = List.copyOf(Objects.requireNonNull(fixedStops, "경유 지점 목록이 없다"));
    }
}
