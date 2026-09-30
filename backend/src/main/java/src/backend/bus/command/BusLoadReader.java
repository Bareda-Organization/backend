package src.backend.bus.command;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 차량의 오늘 이후 회차별 배정 인원을 읽는 창구 — 정원 축소 경고(§5.12, BR-116)가 쓴다. 구현은 {@code run} 모듈에
 * 있다({@code run.roster.ProjectedBusLoadReader}) — {@code bus} 는 하위 모듈이라 {@code run} 을 직접 부르면
 * 역방향 참조가 된다(ARCHITECTURE §3.3).
 */
public interface BusLoadReader {

    /** 오늘 이후 · 미취소 · {@code idle}·{@code confirmed} 회차마다의 배정 인원. */
    List<RunLoad> assignedCountsByRun(Long academyId, Long busId);

    /**
     * 회차 1건의 배정 인원과 그 회차를 알아볼 값(BR-274) — {@code direction} 은 응답 표기({@code to_academy}·{@code from_academy})다.
     * {@code bus} 가 {@code run} 의 enum 을 직접 알지 않게 문자열로 넘긴다.
     */
    record RunLoad(Long runId, LocalDate serviceDate, OffsetDateTime departTime, String direction, int assignedCount) {
    }
}
