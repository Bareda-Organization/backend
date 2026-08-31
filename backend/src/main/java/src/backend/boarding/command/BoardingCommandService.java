package src.backend.boarding.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
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
import src.backend.boarding.entity.VerifyMethod;
import src.backend.boarding.event.RiderNoShowEvent;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.event.RunEndedEvent;
import src.backend.boarding.repository.RiderStatusHistoryRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.command.RunCompletionService;
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
 */
@Service
@RequiredArgsConstructor
public class BoardingCommandService {

    /** 미승차 케이스 대기 만료 — 3분(API_SPEC §4.6 {@code no_show_case.expires_at}). */
    private static final Duration NO_SHOW_EXPIRY = Duration.ofMinutes(3);

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    /**
     * 하원 자동 종료 판정(목표 10, T2 소유) 협력자 — 이 클래스는 마지막 하차 뒤 호출만 하고 전이
     * 로직 자체는 구현하지 않는다. {@code alighted} 처리마다 무조건 호출해도 안전하다(내부에서
     * 하원·종료 대상이 아니면 no-op).
     */
    private final RunCompletionService runCompletionService;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 승하차 처리(BRD-01·02·04·06) — {@code client_key} 재전송(목표 12)은 새 이력·이벤트를 남기지
     * 않고 이미 처리된 결과를 그대로 재구성해 돌려준다. 그 재구성이 이 메서드의 <b>가장 먼저</b>
     * 갈리는 분기다 — 회차 상태·탑승자 존재는 최초 처리 시점에 이미 확인됐던 것이라 재확인하면
     * 그 사이 회차가 종료된 정상 재전송까지 {@code 409} 로 막아 버린다.
     */
    @Transactional
    public RiderStatusUpdateResponse updateStatus(AuthUser requester, Long runId, Long riderId,
            RiderStatusUpdateRequest request) {
        requireEscort(requester);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        Optional<RiderStatusHistory> replay = riderStatusHistoryRepository.findByClientKey(request.clientKey());
        if (replay.isPresent()) {
            return replayResponse(replay.get());
        }

        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        RunRider rider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));

        RiderStatus targetStatus = parseTargetStatus(request.status());
        VerifyMethod verifyMethod = parseVerifyMethod(request.verifyMethod());
        RiderStatus fromStatus = rider.getStatus();
        OffsetDateTime now = OffsetDateTime.now(clock);

        applyTransition(rider, targetStatus, now);
        riderStatusHistoryRepository.save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(),
                fromStatus, targetStatus, false, null, verifyMethod, request.clientKey(), request.occurredAt(),
                ActorType.ESCORT, now, requester.accountId())));

        if (targetStatus == RiderStatus.NO_SHOW) {
            return handleNoShow(run, rider, now);
        }
        if (targetStatus == RiderStatus.ALIGHTED) {
            notifyIfRunJustEnded(run, now);
        }
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                rider.getStudentId(), rider.getId(), statusName(targetStatus), now));
        return RiderStatusUpdateResponse.of(rider.getId(), statusName(targetStatus), now, false);
    }

    /**
     * 상태 정정(BRD-05, 목표 13·14) — 가장 최근 이력 행의 {@code from_status} 로 되돌린다. 횟수·시간
     * 제한을 두지 않는다(목표 14) — 되돌린 결과도 새 이력 행으로 남으므로, 그 새 행의
     * {@code from_status} 를 기준으로 다시 되돌리면 두 상태를 오가는 반복이 그대로 허용된다.
     *
     * <p>이력이 아직 없는 탑승자(승하차 처리를 한 번도 받지 않은 경우)는 {@link RunRider#uponConfirmation}
     * 의 초기값인 {@link RiderStatus#WAITING} 을 직전 상태로 간주한다 — API_SPEC §4.7 에러 목록에
     * 이 경우를 위한 별도 코드가 없어, 새 에러 코드를 만들지 않고 엔티티가 이미 보장하는 초기값으로
     * 처리했다(판단 근거로 보고에 남긴다).
     */
    @Transactional
    public RiderRevertResponse revert(AuthUser requester, Long runId, Long riderId, RiderRevertRequest request) {
        requireEscort(requester);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        RunRider rider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));

        RiderStatus fromStatus = rider.getStatus();
        RiderStatus targetStatus = riderStatusHistoryRepository
                .findFirstByRunRiderIdOrderByChangedAtDescIdDesc(rider.getId())
                .map(RiderStatusHistory::getFromStatus)
                .orElse(RiderStatus.WAITING);
        OffsetDateTime now = OffsetDateTime.now(clock);

        rider.revertTo(targetStatus, now);
        riderStatusHistoryRepository.save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(),
                fromStatus, targetStatus, true, request.reason(), null, null, null, ActorType.ESCORT, now,
                requester.accountId())));

        return new RiderRevertResponse(statusName(targetStatus), now);
    }

    /** C-06 — 동승자가 아니면 회차·탑승자 조회보다 먼저 걸린다(다른 학원 자원 존재 여부를 흘리지 않기 위해서도 그렇다). */
    private void requireEscort(AuthUser requester) {
        if (requester.role() != Role.ESCORT) {
            throw new BusinessException(ErrorCode.ESCORT_ONLY);
        }
    }

    private void applyTransition(RunRider rider, RiderStatus targetStatus, OffsetDateTime now) {
        switch (targetStatus) {
            case BOARDED -> rider.board(now);
            case ALIGHTED -> rider.alight(now);
            case NO_SHOW -> rider.markNoShow(now);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 하원 자동 종료 경계(목표 10, T2 소유) — 방금 처리한 하차가 그 회차의 마지막 잔여 탑승자였는지를
     * {@link RunCompletionService} 에 위임해 묻고, 그 호출로 실제 종료됐을 때만
     * {@link RunEndedEvent} 를 발행한다. 반환값을 무시하고 매번(또는 전혀) 발행하면 알림이 잔류자가
     * 있는 회차에도 나가거나 정작 종료된 회차에서 나가지 않는 결함이 된다 — 이 분기 자체가 T3 이
     * 검사해야 할 갈래다(전이 로직은 여기서 구현하지 않는다).
     */
    private void notifyIfRunJustEnded(Run run, OffsetDateTime now) {
        boolean justEnded = runCompletionService.completeIfAllAlighted(run, now);
        if (justEnded) {
            eventPublisher.publishEvent(new RunEndedEvent(run.getId(), run.getAcademyId(), now));
        }
    }

    /**
     * 미승차 케이스 생성(목표 7) — 3분 뒤 만료로 저장하고, 학부모·관계자 두 갈래 알림의 재료가 될
     * {@link RiderNoShowEvent} 를 발행한다.
     *
     * <p>{@code stop_skipped}(API_SPEC §4.6, C-05)는 항상 {@code false} 로 둔다 — 그 계산은 확정
     * 노선({@code ConfirmedRoute}·{@code RunStop})을 참조해야 하는데 이 서비스는 그 협력자를 주입받지
     * 않는다. 이 태스크(T3)의 6개 목표 중 이 필드를 검사하는 목표가 없어 최소 구현으로 남겼다 —
     * 우려 사항으로 보고에 남긴다.
     */
    private RiderStatusUpdateResponse handleNoShow(Run run, RunRider rider, OffsetDateTime now) {
        OffsetDateTime expiresAt = now.plus(NO_SHOW_EXPIRY);
        NoShowCase noShowCase = noShowCaseRepository.save(NoShowCase.forRunRider(rider.getId(), now, expiresAt, now));
        eventPublisher.publishEvent(new RiderNoShowEvent(run.getId(), run.getAcademyId(), rider.getStudentId(),
                rider.getId(), noShowCase.getId(), now));

        RiderStatusUpdateResponse.NoShowCaseSummary summary = new RiderStatusUpdateResponse.NoShowCaseSummary(
                noShowCase.getId(), noShowCase.getStartedAt(), noShowCase.getExpiresAt());
        return RiderStatusUpdateResponse.withNoShowCase(rider.getId(), now, summary, false);
    }

    /**
     * 재전송 응답 재구성(목표 12) — 새로 아무것도 만들지 않고 최초 처리 결과를 그대로 옮긴다.
     * {@code no_show_case} 는 최초 처리 때 만들어진 케이스를 다시 찾아 싣는다.
     */
    private RiderStatusUpdateResponse replayResponse(RiderStatusHistory history) {
        RiderStatus toStatus = history.getToStatus();
        if (toStatus != RiderStatus.NO_SHOW) {
            return RiderStatusUpdateResponse.of(history.getRunRiderId(), statusName(toStatus),
                    history.getChangedAt(), false);
        }
        return noShowCaseRepository.findByRunRiderId(history.getRunRiderId())
                .map(noShowCase -> RiderStatusUpdateResponse.withNoShowCase(history.getRunRiderId(),
                        history.getChangedAt(),
                        new RiderStatusUpdateResponse.NoShowCaseSummary(noShowCase.getId(),
                                noShowCase.getStartedAt(), noShowCase.getExpiresAt()),
                        false))
                .orElseGet(() -> RiderStatusUpdateResponse.of(history.getRunRiderId(), statusName(toStatus),
                        history.getChangedAt(), false));
    }

    /** {@code boarded} · {@code no_show} · {@code alighted} 셋만 허용한다(API_SPEC §4.6 요청 표) — 그 외는 422. */
    private static RiderStatus parseTargetStatus(String status) {
        return switch (status) {
            case "boarded" -> RiderStatus.BOARDED;
            case "alighted" -> RiderStatus.ALIGHTED;
            case "no_show" -> RiderStatus.NO_SHOW;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    private static VerifyMethod parseVerifyMethod(String verifyMethod) {
        return switch (verifyMethod) {
            case "photo" -> VerifyMethod.PHOTO;
            case "manual" -> VerifyMethod.MANUAL;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    private static String statusName(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
