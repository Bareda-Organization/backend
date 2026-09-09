package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.command.ChangeRequestAutoRejectionService;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.domain.RunStartWindowPolicy;
import src.backend.run.dto.RunStartResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;

/**
 * 기사의 운행 시작 처리(API_SPEC §4.4, RUN-02·M-10) — Phase 9 goal 1(±10분 창) · goal 2(운행 시작
 * 알림) · goal 3(미결 변경 요청 즉시 종결) · goal 11(하원 전원 자동 탑승, C-07)을 한 트랜잭션에 묶는다.
 *
 * <p>{@link ChangeRequestAutoRejectionService#terminateForRun} 을 <b>같은 트랜잭션에서 동기
 * 호출</b>하는 것이 그 서비스 자신의 javadoc 이 요구하는 배선이다 — moving 전이와 분리하면 최대
 * 30초의 창이 남는다(그 클래스 javadoc 참고).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunStartCommandService {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final RunStartWindowPolicy runStartWindowPolicy;

    private final ChangeRequestAutoRejectionService changeRequestAutoRejectionService;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public RunStartResponse start(AuthUser requester, Long runId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        if (run.getStatus() == RunStatus.MOVING || run.getStatus() == RunStatus.FINISHED) {
            throw new BusinessException(ErrorCode.RUN_ALREADY_STARTED);
        }
        if (run.getStatus() != RunStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!runStartWindowPolicy.isWithinWindow(run.getDepartTime(), now)) {
            throw new BusinessException(ErrorCode.START_WINDOW_CLOSED);
        }

        run.start(now);
        changeRequestAutoRejectionService.terminateForRun(requester.academyId(), runId, now);

        Integer autoBoardedCount = null;
        if (run.getDirection() == Direction.FROM_ACADEMY) {
            autoBoardedCount = autoBoardWaitingRiders(run, now);
        }

        eventPublisher.publishEvent(
                new RunStartedEvent(runId, requester.academyId(), now, autoBoardedCount == null ? 0 : autoBoardedCount));

        return RunStartResponse.of(run, autoBoardedCount);
    }

    /** 하원 회차 시작 시 대기 중인 탑승자 전원을 태운다(C-07·BRD-03) — 이미 다른 상태인 행은 건드리지 않는다. */
    private int autoBoardWaitingRiders(Run run, OffsetDateTime now) {
        List<RunRider> waiting = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId())
                .stream()
                .filter(rider -> rider.getStatus() == RiderStatus.WAITING)
                .toList();
        waiting.forEach(rider -> rider.board(now));
        return waiting.size();
    }
}
