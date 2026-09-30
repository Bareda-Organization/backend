package src.backend.boarding.command;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;

/**
 * 그 승하차지의 {@code skipped} 표시를 다루는 협력자(C-05, API_SPEC §4.6 {@code stop_skipped}) —
 * {@link BoardingCommandService} 의 미승차 처리·되돌리기 두 경로가 함께 쓰는 판정이라 뗐다(§20.2,
 * BR-101 — 원래 한 클래스에 있어 한쪽 경로를 고칠 때 다른 쪽의 같은 판정을 놓치기 쉬웠다).
 *
 * <p>{@code @Transactional(propagation = MANDATORY)} 다 — 호출부가 이미 연 승하차 처리 트랜잭션
 * 안에서 같이 커밋돼야 한다({@code run.command.RunCompletionService} 와 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class StopSkipJudge {

    /** 운행 중 미승차로 잔여 0명이 된 정차지에 남기는 사유(C-05, {@code run_stop.skip_notice}). */
    private static final String SKIP_NOTICE = "운행 중 미승차로 전원 미탑승";

    /** 잔여 판정 시 제외할 상태 — 이미 결석·미승차로 처리된 탑승자는 잔여가 아니다(목표 7). */
    private static final List<RiderStatus> NOT_REMAINING = List.of(RiderStatus.ABSENT, RiderStatus.NO_SHOW);

    private final RunRiderRepository runRiderRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    /**
     * 그 승하차지에 아직 남은(부재·미승차 둘 다 빠진) 탑승자가 0명이면 확정 노선의 정차 항목을
     * {@code skipped} 로 표시하고 {@code true} 를 돌려준다(C-05 후자 경로, §4.6 {@code stop_skipped}).
     *
     * <p>{@code no_show} 도 잔여 판정에서 빼야 한다(목표 7) — 호출 직전에 그 탑승자 자신이 이미
     * {@code NO_SHOW} 로 바뀐 상태라, {@code ABSENT} 만 빼면 자신을 스스로 "남은 사람"으로 세어
     * 잔여가 영원히 0이 되지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean skipIfNoRidersRemain(Long runId, Long stopId) {
        // 정차 항목을 먼저 잠근다(BR-229) — 같은 승하차지의 다른 미승차 처리가 커밋한 뒤에 세야 마지막 한 명이 잔여 0을 본다.
        Optional<RunStop> lockedRunStop = currentRunStop(runId, stopId);
        long remaining = runRiderRepository.countByRunIdAndStopIdAndStatusNotIn(runId, stopId, NOT_REMAINING);
        if (remaining > 0) {
            return false;
        }
        return lockedRunStop
                .map(runStop -> {
                    runStop.markSkipped(SKIP_NOTICE);
                    return true;
                })
                .orElse(false);
    }

    /** 되돌리기가 {@code no_show} 를 벗어날 때 그 정차지의 skipped 표시를 푼다(BR-009) — 탑승자가 다시 잔여로 세어진다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearSkipped(Long runId, Long stopId) {
        currentRunStop(runId, stopId).ifPresent(RunStop::clearSkipped);
    }

    /** 확정 노선 현재 버전에서 그 승하차지의 정차 항목을 행 잠금으로 읽는다 — 확정 노선이 아직 없으면 비어 있다. */
    private Optional<RunStop> currentRunStop(Long runId, Long stopId) {
        return confirmedRouteRepository.findById(runId)
                .map(ConfirmedRoute::getCurrentVersionId)
                .flatMap(versionId -> runStopRepository.findLockedByRouteVersionIdAndStopId(versionId, stopId));
    }
}
