package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.AutoBoardingService;
import src.backend.boarding.entity.RunRider;
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
import src.backend.run.event.RunAutoBoardedEvent;
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

    @PersistenceContext
    private EntityManager entityManager;

    private final AutoBoardingService autoBoardingService;

    private final RunAssignmentAccess runAssignmentAccess;

    private final RunStartWindowPolicy runStartWindowPolicy;

    private final ChangeRequestAutoRejectionService changeRequestAutoRejectionService;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 시작 창(±10분) 안인지 확인하고 confirmed → moving 전이 + 미결 변경 요청 종결 + 하원 자동
     * 탑승을 한 트랜잭션에 묶는다(§4.4).
     *
     * <p>CODE_CONVENTIONS §20.2 — 본문이 기준(20줄)을 간발의 차로 넘긴다. 네 목표(창 확인·전이·요청 종결·자동 탑승)가
     * 전부 이 한 트랜잭션 커밋에 같이 들어가야 하는 게 요점이라(클래스 javadoc), 쪼개면 그 "같이"를
     * 코드에서 더 이상 한눈에 볼 수 없다.
     */
    public RunStartResponse start(AuthUser requester, Long runId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        // 회차 행을 잠그고 다시 읽은 뒤 판정한다(BR-041, §7 규칙 3) — 시작 요청 두 건이 겹치면 늦은 쪽은
        // 앞쪽 커밋 뒤의 moving 을 보고 409 로 끝난다. 잠그지 않으면 둘 다 confirmed 를 보고 통과해 운행 시작
        // 알림이 두 번 나간다.
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);

        if (run.isCanceled()) {
            // 취소 뒤에도 confirmed 로 남으므로 상태 판정보다 먼저 본다(BR-042).
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
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

    /**
     * 하원 회차 시작 시 대기 중인 탑승자 전원을 태운다(C-07·BRD-03) — 이미 다른 상태인 행은 건드리지 않는다.
     * 전이 이력(actor_type=system)은 {@link AutoBoardingService} 가 남기고, 태운 학생이 있으면 승차 알림의
     * 재료({@link RunAutoBoardedEvent})를 발행한다(NTF-01, R51 H1).
     */
    private int autoBoardWaitingRiders(Run run, OffsetDateTime now) {
        List<RunRider> boarded = autoBoardingService.boardAllForDropOff(run.getId(), now);
        if (!boarded.isEmpty()) {
            eventPublisher.publishEvent(new RunAutoBoardedEvent(run.getId(), run.getAcademyId(),
                    boarded.stream().map(RunRider::getStudentId).toList(), now));
        }
        return boarded.size();
    }
}
