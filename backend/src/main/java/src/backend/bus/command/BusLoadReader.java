package src.backend.bus.command;

import java.util.Map;

/**
 * 차량의 오늘 이후 회차별 배정 인원을 읽는 창구 — 정원 축소 경고(§5.12, BR-116)가 쓴다. 구현은 {@code run} 모듈에
 * 있다({@code run.roster.ProjectedBusLoadReader}) — {@code bus} 는 하위 모듈이라 {@code run} 을 직접 부르면
 * 역방향 참조가 된다(ARCHITECTURE §3.3).
 */
public interface BusLoadReader {

    /** 회차 id → 배정 인원. 오늘 이후 · 미취소 · {@code idle}·{@code confirmed} 회차만. */
    Map<Long, Integer> assignedCountsByRun(Long academyId, Long busId);
}
