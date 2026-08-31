package src.backend.run.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import src.backend.run.entity.Run;

/**
 * 승하차지 도착 처리 응답(API_SPEC §4.5) — {@code nextStop} 은 최종 지점 처리일 때 {@code null},
 * {@code autoAlightedCount} 는 등원 최종 지점 처리일 때만, {@code remaining} 은 하원 최종 지점에서
 * 미하차 잔류가 남아 종료가 보류될 때만 채워진다(그 외에는 빈 목록) — 셋 다 "이번 도착 처리가
 * 무엇을 겸했는가"에 따라 갈리는 값이라 응답 하나에 모두 실어도 대부분은 비어 있는 것이 정상이다.
 */
public record RunArriveResponse(OffsetDateTime arrivedAt, NextStopResponse nextStop, boolean isFinal,
        String runStatus, boolean finishPending, List<RemainingRiderResponse> remaining, Integer autoAlightedCount) {

    public static RunArriveResponse of(OffsetDateTime arrivedAt, NextStopResponse nextStop, boolean isFinal,
            Run run, List<RemainingRiderResponse> remaining, Integer autoAlightedCount) {
        return new RunArriveResponse(arrivedAt, nextStop, isFinal, run.getStatus().name().toLowerCase(Locale.ROOT),
                run.isFinishPending(), remaining, autoAlightedCount);
    }

    /** 기사 화면 포인터가 다음으로 가리킬 정차 항목. */
    public record NextStopResponse(Long stopId, String stopName) {
    }

    /** 하원 미하차 잔류 1명 — 이름·현재 승하차지를 함께 실어 기사 화면이 별도 조회 없이 표시한다. */
    public record RemainingRiderResponse(Long riderId, String name, String stopName) {
    }
}
