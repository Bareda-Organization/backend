package src.backend.boarding.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.RiderRevertRequest;
import src.backend.boarding.dto.RiderRevertResponse;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.boarding.entity.ActorType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RiderStatusHistory;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RiderStatusHistoryRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.command.RunRowLock;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 승하차 처리(API_SPEC §4.6)·되돌리기(§4.7) 커맨드 — 동승자 전용(C-06, Ruling 203).
 *
 * <p>권한 판정을 {@code hasAuthority(...)} 애너테이션이 아니라 이 클래스 안에서 직접 한다 — Spring
 * Security 게이트를 거치면 거부가 {@link org.springframework.security.access.AccessDeniedException}
 * 이 되어 {@code GlobalExceptionHandler} 가 일반 {@code 403 FORBIDDEN} 으로 답하는데, §4.6·§4.7 이
 * 요구하는 값은 도메인 특정 코드인 {@code 403 ESCORT_ONLY} 다. 컨트롤러는 {@code @AuthenticatedOnly}
 * (인증 여부만)만 걸고 이 클래스가 진짜 권한 판정을 한다.
 *
 * <p><b>배치 판정 순서(§1.11, Ruling 259(b))</b> — 역할 확인({@link EscortGuard#require}) 다음으로
 * {@link RunAssignmentAccess#assertAssignedDriverOrEscort} 를 회차 조회보다 먼저 부른다. 배치는
 * 매니저·회차가 같은 학원일 때만 생성되므로({@code AssignmentCommandService#place}), 배치가 없다는
 * 사실 하나로 회차 없음·타 학원·미배치 세 경우가 구별 없이 {@code 403 FORBIDDEN} 이 된다 — 그 뒤의
 * {@code findByIdAndAcademyId} 는 도달할 일이 거의 없는 방어 조회로 남는다.
 */
@Service
@RequiredArgsConstructor
public class BoardingCommandService {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    /** 미승차 케이스 생성 · 재개와 되돌리기 뒤 정합 협력자. */
    private final NoShowStep noShowStep;

    /** 재전송 응답 재구성 협력자(BR-226 키 잠금 포함). */
    private final BoardingReplay boardingReplay;

    /** 승하차지 출발 여부 판정(행 잠금)과 출발 뒤 되돌리기 거부 협력자. */
    private final StopDepartureGuard stopDepartureGuard;

    /** PATCH 승하차 처리의 전이 적용 · 이력 기록 · 목표 상태별 후속 처리 협력자. */
    private final RiderStatusRecorder riderStatusRecorder;

    private final RunAssignmentAccess runAssignmentAccess;

    /** 회차 행 잠금 — 모든 쓰기 경로가 탑승자 · 승하차지 행보다 먼저 쥐는 잠금 순서의 진입점이다({@link RunRowLock}). */
    private final RunRowLock runRowLock;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 승하차 처리(BRD-01·02·04·06) — {@code client_key} 재전송은 새 이력·이벤트를 남기지
     * 않고 이미 처리된 결과를 그대로 재구성해 돌려준다. 그 재구성이 이 메서드의 <b>가장 먼저</b>
     * 갈리는 분기다 — 회차 상태·탑승자 존재는 최초 처리 시점에 이미 확인됐던 것이라 재확인하면
     * 그 사이 회차가 종료된 정상 재전송까지 {@code 409} 로 막아 버린다.
     */
    @Transactional
    public RiderStatusUpdateResponse updateStatus(AuthUser requester, Long runId, Long riderId,
            RiderStatusUpdateRequest request) {
        EscortGuard.require(requester);
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = findRun(requester, runId);

        Optional<RiderStatusUpdateResponse> replayed = boardingReplay.replay(request, runId, riderId);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        lockMovingRun(run);
        RunRider rider = findRider(runId, riderId);
        return riderStatusRecorder.record(requester, run, rider, request);
    }

    /**
     * 상태 정정(BRD-05, API_SPEC §4.7) — 가장 최근 이력 행의 {@code from_status} 로 되돌린다. 횟수
     * 제한은 두지 않는다(Ruling 305) — 되돌린 결과도 새
     * 이력 행으로 남으므로, 그 새 행의 {@code from_status} 를 기준으로 다시 되돌리면 두 상태를
     * 오가는 반복이 그대로 허용된다. 단, 같은 Ruling 305 가 **승하차지를 떠난 뒤에는 되돌리기를
     * 막는다** — {@link StopDepartureGuard#assertNotDeparted} 참고.
     *
     * <p>이력이 아직 없는 탑승자(승하차 처리를 한 번도 받지 않은 경우)는 {@link RunRider#uponConfirmation}
     * 의 초기값인 {@link RiderStatus#WAITING} 을 직전 상태로 간주한다 — API_SPEC §4.7 에러 목록에
     * 이 경우를 위한 별도 코드가 없어, 새 에러 코드를 만들지 않고 엔티티가 이미 보장하는 초기값으로
     * 처리했다(판단 근거로 보고에 남긴다).
     *
     * <p><b>정정 알림은 부재다(Ruling 308 이 Ruling 219 를 대체)</b> — 출발 전 되돌리기는 발송 전
     * 수정이라 정정할 대상이 없다. {@code RiderStatusChangedEvent} 는 그대로 발행한다 —
     * {@code rider_changed} WebSocket 방송(§7.1)이 그 이벤트를 구독해 "지금 상태"를 방송하는
     * 재료로 쓰는데, 이 되돌리기도 그 트리거 표에 이미 명시돼 있다(아래 주석).
     */
    @Transactional
    public RiderRevertResponse revert(AuthUser requester, Long runId, Long riderId, RiderRevertRequest request) {
        EscortGuard.require(requester);
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = findRun(requester, runId);
        lockMovingRun(run);
        RunRider rider = findRider(runId, riderId);
        stopDepartureGuard.assertNotDeparted(run.getId(), rider.getStopId());

        RiderStatus fromStatus = rider.getStatus();
        RiderStatus targetStatus = previousStatusOf(rider);
        OffsetDateTime now = OffsetDateTime.now(clock);

        rider.revertTo(targetStatus, now);
        riderStatusHistoryRepository.save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(),
                fromStatus, targetStatus, true, request.reason(), null, null, null, ActorType.ESCORT, now,
                requester.accountId())));
        noShowStep.syncNoShowAfterRevert(run, rider, fromStatus, targetStatus, now);
        publishRevertedEvent(run, rider, targetStatus, now);
        return new RiderRevertResponse(BoardingRules.statusName(targetStatus), now);
    }

    /** 요청자의 학원 범위에서 회차를 읽는다 — 없으면 {@code RUN_NOT_FOUND}. */
    private Run findRun(AuthUser requester, Long runId) {
        return runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    /** 회차 행 잠금을 쥐고 운행 중인지 확인한다 — 아니면 {@code RUN_NOT_MOVING}. */
    private void lockMovingRun(Run run) {
        runRowLock.acquire(run);
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
    }

    /** 그 회차의 결석 아닌 탑승자를 읽는다 — 없으면 {@code RIDER_NOT_FOUND}. */
    private RunRider findRider(Long runId, Long riderId) {
        return runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));
    }

    /** 가장 최근 이력 행의 직전 상태 — 이력이 없으면 {@link RiderStatus#WAITING}. */
    private RiderStatus previousStatusOf(RunRider rider) {
        return riderStatusHistoryRepository.findFirstByRunRiderIdOrderByChangedAtDescIdDesc(rider.getId())
                .map(RiderStatusHistory::getFromStatus)
                .orElse(RiderStatus.WAITING);
    }

    private void publishRevertedEvent(Run run, RunRider rider, RiderStatus targetStatus, OffsetDateTime now) {
        // WebSocket rider_changed 방송(API_SPEC §7.1)의 트리거 표가 revert 도 명시한다.
        // 알림 로그 적재 리스너(BoardingNotificationListener)는 이제 이 이벤트를 구독하지 않는다 —
        // 출발 전 되돌리기는 발송 전 수정이라 정정할 대상이 없다(Ruling 308 이 Ruling 219 를 대체).
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                rider.getStudentId(), rider.getId(), BoardingRules.statusName(targetStatus), now, true));
    }
}
