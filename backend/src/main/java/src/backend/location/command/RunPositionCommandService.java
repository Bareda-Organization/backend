package src.backend.location.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRequest;
import src.backend.location.entity.RunPosition;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.repository.RunPositionRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 기사 단말의 위치 송신 처리(API_SPEC §4.12, LOC-01, 목표 1·2·3) —
 * {@link src.backend.run.command.RunArrivalCommandService} 와 같은 인가·상태 판정 순서를 따른다
 * (배치 기사인지 → 회차 존재 → {@code moving} 인지).
 *
 * <p><b>{@code run_position} 적재가 먼저, Redis 갱신은 커밋 후</b>(조율자 판단, 목표 3) — 이력 유실은
 * 되돌릴 수 없지만 Redis 만 유실되면 5~10초 뒤 다음 송신이 덮어써 스스로 회복되므로, 회복 가능한
 * 쪽을 나중에 둔다. 이 서비스는 이벤트만 발행하고, 실제 Redis 쓰기는
 * {@link RunPositionRedisListener} 가 {@code AFTER_COMMIT} 에서 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunPositionCommandService {

    private final RunRepository runRepository;

    private final RunPositionRepository runPositionRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public void receive(AuthUser requester, Long runId, RunPositionRequest request) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        RunPosition position = RunPosition.onReceive(runId, request.lat(), request.lng(),
                request.recordedAt(), receivedAt);
        runPositionRepository.save(position);

        eventPublisher.publishEvent(
                new RunPositionReceivedEvent(runId, request.lat(), request.lng(), request.recordedAt(), receivedAt));
    }
}
