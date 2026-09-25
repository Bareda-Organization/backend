package src.backend.run.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.manager.entity.Assignment;
import src.backend.run.domain.RunStartWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * 매니저 앱의 담당 회차 카드 1건(API_SPEC §4.1 {@code GET /manager/runs}, RUN-01·M-02·M-07,
 * Phase 9 목표 6·15·16·17).
 *
 * <p>{@code confirmed} 는 {@code run_status} 의 파생값이다({@code idle} 이 아니면 {@code true}) —
 * 별도 컬럼이 아니라 목록 화면이 "명단 진입 가능" 배지를 매번 상태값과 비교해 다시 계산하지 않도록
 * 여기서 한 번 계산해 싣는다.
 *
 * <p>§20.2 — 필드 15개를 넘긴 채 둔다(2026-09-25 검사 BR-101). 필드 목록은 API_SPEC §4.1 응답
 * 스키마가 정한 계약이라 코드 쪽에서 임의로 줄이거나 쪼갤 수 없다 — 줄이려면 사양을 먼저 바꿔야 한다.
 *
 * @param startWindow 운행 시작 버튼 활성 창 — 출발 시각 ±10분(FEATURE_SPEC §2.1)
 * @param addedCount 이 회차의 명단에 ②구간 승인으로 추가된({@code run_rider.change=added}) 인원 수
 * @param removedCount 같은 방식으로 제외된({@code change=removed}) 인원 수
 * @param ackRequired 노선 변경 확인 응답 미완료 여부(RUN-07) — {@code assignment.acked_route_version_id}
 *         가 {@code confirmed_route.current_version_id} 와 다르면 {@code true}
 * @param roleInRun {@code driver} · {@code escort} — 화면 구성을 가른다
 */
public record ManagerRunResponse(String runId, String busNo, String direction, OffsetDateTime departTime,
        String origin, String destination, Integer estDurationMin, String runStatus, boolean confirmed,
        OffsetDateTime confirmAt, StartWindow startWindow, long addedCount, long removedCount,
        boolean ackRequired, String roleInRun) {

    /** {@link Run}·배치·명단 증감 건수를 매니저 앱 카드 1건으로 조립한다. */
    public static ManagerRunResponse of(Run run, String busNo, Assignment assignment, long addedCount,
            long removedCount, boolean ackRequired, RunStartWindowPolicy startWindowPolicy) {
        OffsetDateTime departTime = run.getDepartTime();
        // §20.4 — 창 값은 RunStartWindowPolicy 가 유일한 소유(BR-101, 2026-09-25 검사).
        StartWindow startWindow = new StartWindow(startWindowPolicy.earliestStart(departTime),
                startWindowPolicy.latestStart(departTime));
        return new ManagerRunResponse(String.valueOf(run.getId()), busNo, lower(run.getDirection().name()),
                departTime, run.getOriginName(), run.getDestinationName(), run.getEstDurationMin(),
                lower(run.getStatus().name()), run.getStatus() != RunStatus.IDLE, run.getConfirmAt(),
                startWindow, addedCount, removedCount, ackRequired, lower(assignment.getRole().name()));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** 운행 시작 버튼 활성 창(§4.1 {@code start_window}) — 출발 시각 ±10분. */
    public record StartWindow(OffsetDateTime from, OffsetDateTime to) {
    }
}
