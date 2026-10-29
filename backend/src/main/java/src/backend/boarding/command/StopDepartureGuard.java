package src.backend.boarding.command;

import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;

/** 승하차지 출발 여부를 {@code run_stop} 행 잠금으로 읽고, 출발 뒤 되돌리기를 막는 판정 협력자. 호출부 트랜잭션 안에서만 부른다. */
@Component
@RequiredArgsConstructor
class StopDepartureGuard {

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    /**
     * 승하차지를 떠난 뒤의 되돌리기를 막는다(Ruling 307) — {@code run_stop.departed_at IS
     * NOT NULL} 이 유일한 판정 기준이다({@link src.backend.location.proximity.ProximityNotificationService
     * #judgeRun} 가 도착 후 100m 이탈 최초 1회를 이 컬럼에 기록한다). 뒤 순번 정차지 참조에 기대던 파생 규칙은
     * 폐기했다 — 마지막 승하차지는 뒤 순번이 없어 영원히 되돌릴 수 있는 구멍이 있었다.
     *
     * <p>출발 여부를 <b>행 잠금</b>으로 읽는다({@link #isStopDepartedLocked}) — 잠금 없이 읽으면 출발 선점이 이 읽기와 커밋 사이에 끼어
     * 출발 알림이 나간 뒤 되돌려진다. 확정 노선(버전)이 아직 없거나 그 학생의 정차 항목 자체를 찾지 못하면 판정 재료가
     * 없으므로 통과시킨다(막을 근거가 없는 채로 막으면 정상 되돌리기까지 거부하게 된다).
     */
    void assertNotDeparted(Long runId, Long stopId) {
        if (isStopDepartedLocked(runId, stopId)) {
            throw new BusinessException(ErrorCode.STOP_ALREADY_DEPARTED);
        }
    }

    /**
     * 그 승하차지가 이미 출발 처리됐는지를 <b>행 잠금</b>으로 읽는다(Ruling 878) — 출발 선점({@code claimDeparture} UPDATE)과 같은
     * {@code run_stop} 행을 쥐므로 둘 중 먼저 온 쪽이 커밋할 때까지 다른 쪽이 기다린다. 기록이 먼저면 출발 때 묶음 발송이 이 기록을
     * 읽고, 출발이 먼저면 이 기록이 즉시 발송한다. 확정 노선이 없으면 판정 재료가 없어 출발 전으로 본다.
     */
    boolean isStopDepartedLocked(Long runId, Long stopId) {
        return currentRouteVersionId(runId)
                .flatMap(versionId -> runStopRepository.findLockedByRouteVersionIdAndStopId(versionId, stopId))
                .map(runStop -> runStop.getDepartedAt() != null).orElse(false);
    }

    private Optional<Long> currentRouteVersionId(Long runId) {
        return confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId);
    }
}
