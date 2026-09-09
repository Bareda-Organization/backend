package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Assignment;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.dto.RunAckChangesResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 기사·동승자의 노선 변경 확인 응답 처리(API_SPEC §4.11, RUN-07·M-04).
 *
 * <p>요청의 {@code change_ids[]} 는 받되 쓰지 않는다 — {@link Assignment#ack} 의 javadoc이 이미
 * 밝히듯 스키마가 변경 건 단위 확인을 담을 자리가 없고({@code acked_route_version_id} 가 배포 버전
 * 1개만 가리킨다), 요청에 그 필드가 와도 이 서비스는 현재 배포 버전 전체를 확인 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunAckChangesCommandService {

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final Clock clock;

    public RunAckChangesResponse ackChanges(AuthUser requester, Long runId) {
        Assignment assignment = runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        assignment.ack(currentVersionIdOf(runId), now);
        return new RunAckChangesResponse(now);
    }

    /**
     * 그 회차의 현재 배포 버전 — 위에서 이미 {@code RUN_NOT_CONFIRMED}(idle) 를 걸러낸 뒤라
     * {@code confirmed_route} 존재를 전제한다({@code RunConfirmationPersistence} 가 확정과 같은
     * 트랜잭션에서 함께 만든다).
     */
    private Long currentVersionIdOf(Long runId) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        return confirmedRoute.getCurrentVersionId();
    }
}
