package src.backend.monitoring.query;

import java.time.Duration;
import java.util.List;

import src.backend.routing.entity.RunStop;
import src.backend.routing.query.CurrentRunStopResolver;
import src.backend.run.entity.Run;

/**
 * 회차의 지연 분 계산 한 곳(Ruling 232 확정 §3.1) — §5.18 · §5.3 · §6.8 · §6.18 이 같은 값을 쓴다. 가장 최근 도착한 정차
 * ({@link CurrentRunStopResolver} 판정)의 {@code arrived_at − eta}, 도착한 정차가 없으면 {@code started_at − depart_time}.
 * 두 갈래 다 음수는 0 으로 내린다 — 정시·조기 도착을 "마이너스 지연" 으로 보여 주는 것은 관계자에게 혼동만 준다.
 */
public final class RunDelayCalculator {

    private RunDelayCalculator() {
    }

    /** {@code stops} 는 호출부가 읽은 그 회차의 확정 노선 정차 항목이다 — 노선이 없으면 빈 목록. 지연이 없으면 0. */
    public static int minutesOf(Run run, List<RunStop> stops) {
        RunStop lastArrived = CurrentRunStopResolver.resolve(stops).orElse(null);
        if (lastArrived != null && lastArrived.getEta() != null) {
            return nonNegativeMinutes(Duration.between(lastArrived.getEta(), lastArrived.getArrivedAt()));
        }
        if (run.getStartedAt() != null) {
            return nonNegativeMinutes(Duration.between(run.getDepartTime(), run.getStartedAt()));
        }
        return 0;
    }

    private static int nonNegativeMinutes(Duration late) {
        return (int) Math.max(0, late.toMinutes());
    }
}
