package src.backend.boarding.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.AcademySetting;
import src.backend.academy.repository.AcademySettingRepository;
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
import src.backend.location.proximity.StopDepartureService;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.access.RunAssignmentAccess;
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
 *
 * <p><b>배치 판정 순서(§1.11, Ruling 259(b))</b> — 역할 확인({@link #requireEscort}) 다음으로
 * {@link RunAssignmentAccess#assertAssignedDriverOrEscort} 를 회차 조회보다 먼저 부른다. 배치는
 * 매니저·회차가 같은 학원일 때만 생성되므로({@code AssignmentCommandService#place}), 배치가 없다는
 * 사실 하나로 회차 없음·타 학원·미배치 세 경우가 구별 없이 {@code 403 FORBIDDEN} 이 된다 — 그 뒤의
 * {@code findByIdAndAcademyId} 는 도달할 일이 거의 없는 방어 조회로 남는다.
 */
@Service
@RequiredArgsConstructor
public class BoardingCommandService {

    /** 운행 중 미승차로 잔여 0명이 된 정차지에 남기는 사유(C-05, {@code run_stop.skip_notice}). */
    private static final String SKIP_NOTICE = "운행 중 미승차로 전원 미탑승";

    /** 미승차로 잔여 0명 판정 시 제외할 상태 — 이미 결석·미승차로 처리된 탑승자는 잔여가 아니다(목표 7). */
    private static final List<RiderStatus> NOT_REMAINING = List.of(RiderStatus.ABSENT, RiderStatus.NO_SHOW);

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    /**
     * 미승차 대기 시간(분)을 학원별로 읽는다(Phase 11 목표 2, API_SPEC §5.21) — 학원마다 값이 다르고,
     * {@code academy_setting} 행이 아직 없는 학원(구 데이터 · 등록 경로가 아직 안 만들던 시절의 학원)은
     * {@link AcademySetting#DEFAULT_NO_SHOW_WAIT_MINUTES} 로 자가 치유한다({@code AcademySettingQueryService}
     * 의 GET-or-create 와 같은 근거) — 3분 고정 상수를 없애는 것이 이 목표의 본체라, 값이 없다고 예외를
     * 던지면 그 학원의 미승차 처리 자체가 막힌다.
     */
    private final AcademySettingRepository academySettingRepository;

    /**
     * {@code stop_skipped} 계산·반영(API_SPEC §4.6 · C-05)의 협력자 — {@link src.backend.request.command
     * .BoardingIntentCommandService#skipStopIfNoRidersRemain}(③구간 결석 신청)이 확정 노선을 찾는 것과
     * 같은 경로를 그대로 재사용한다.
     */
    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    /**
     * 하원 자동 종료 판정(목표 10, T2 소유) 협력자 — 이 클래스는 마지막 하차 뒤 호출만 하고 전이
     * 로직 자체는 구현하지 않는다. {@code alighted} 처리마다 무조건 호출해도 안전하다(내부에서
     * 하원·종료 대상이 아니면 no-op).
     */
    private final RunCompletionService runCompletionService;

    /**
     * 운행 종료 시 도착·미출발로 남은 승하차지 전부를 강제로 출발 처리하는 협력자(Ruling 312, 목표
     * 8) — 마지막 승하차지는 다음 정차지가 없어 {@code RunArrivalCommandService} 의 폴백(목표 7)을
     * 못 받으므로, 이 시점에 한 번 더 쓸어낸다.
     */
    private final StopDepartureService stopDepartureService;

    private final RunAssignmentAccess runAssignmentAccess;

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
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        Optional<RiderStatusHistory> replay = riderStatusHistoryRepository.findByClientKey(request.clientKey());
        if (replay.isPresent()) {
            return replayResponse(assertSameRequest(replay.get(), runId, riderId, request.status()));
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

        applyTransition(rider, fromStatus, targetStatus, now);
        riderStatusHistoryRepository.save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(),
                fromStatus, targetStatus, false, null, verifyMethod, request.clientKey(), request.occurredAt(),
                ActorType.ESCORT, now, requester.accountId())));

        if (targetStatus == RiderStatus.NO_SHOW) {
            RiderStatusUpdateResponse response = handleNoShow(run, rider, now);
            // 하원 보류 회차 — 마지막 탑승자가 미승차로 빠져도 boarded 0명이다(BR-031, C-15).
            notifyIfRunJustEnded(run, now);
            return response;
        }
        if (targetStatus == RiderStatus.ALIGHTED) {
            notifyIfRunJustEnded(run, now);
        }
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                rider.getStudentId(), rider.getId(), statusName(targetStatus), now, false));
        return RiderStatusUpdateResponse.of(rider.getId(), statusName(targetStatus), now, false);
    }

    /**
     * 상태 정정(BRD-05, API_SPEC §4.7) — 가장 최근 이력 행의 {@code from_status} 로 되돌린다. 횟수
     * 제한은 두지 않는다(2026-09-19 사용자 확정, Ruling 305 — 오픈 이슈 J 해소) — 되돌린 결과도 새
     * 이력 행으로 남으므로, 그 새 행의 {@code from_status} 를 기준으로 다시 되돌리면 두 상태를
     * 오가는 반복이 그대로 허용된다. 단, 같은 Ruling 305 가 **승하차지를 떠난 뒤에는 되돌리기를
     * 막는다** — {@link #assertNotDeparted} 참고.
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
        requireEscort(requester);
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        RunRider rider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));
        assertNotDeparted(run.getId(), rider.getStopId());

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
        syncNoShowAfterRevert(run, rider, fromStatus, targetStatus, now);

        // WebSocket rider_changed 방송(API_SPEC §7.1)의 트리거 표가 revert 도 명시한다 — T2 소유 목표 4·5·6.
        // 알림 로그 적재 리스너(BoardingNotificationListener)는 이제 이 이벤트를 구독하지 않는다 —
        // 출발 전 되돌리기는 발송 전 수정이라 정정할 대상이 없다(Ruling 308 이 Ruling 219 를 대체).
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                rider.getStudentId(), rider.getId(), statusName(targetStatus), now, true));

        return new RiderRevertResponse(statusName(targetStatus), now);
    }

    /** C-06 — 동승자가 아니면 회차·탑승자 조회보다 먼저 걸린다(다른 학원 자원 존재 여부를 흘리지 않기 위해서도 그렇다). */
    private void requireEscort(AuthUser requester) {
        if (requester.role() != Role.ESCORT) {
            throw new BusinessException(ErrorCode.ESCORT_ONLY);
        }
    }

    /**
     * 승하차지를 떠난 뒤의 되돌리기를 막는다(목표 6b, Ruling 307) — {@code run_stop.departed_at IS
     * NOT NULL} 이 유일한 판정 기준이다({@link src.backend.location.proximity.ProximityNotificationService
     * #judgeDeparture} 가 도착 후 100m 이탈 최초 1회를 이 컬럼에 기록한다). 뒤 순번 정차지 참조에 기대던 파생 규칙은
     * 폐기했다 — 마지막 승하차지는 뒤 순번이 없어 영원히 되돌릴 수 있는 구멍이 있었다(목표 6c).
     *
     * <p>확정 노선(버전)이 아직 없거나 그 학생의 정차 항목 자체를 찾지 못하면 판정 재료가 없으므로
     * 통과시킨다(막을 근거가 없는 채로 막으면 정상 되돌리기까지 거부하게 된다).
     */
    private void assertNotDeparted(Long runId, Long stopId) {
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return;
        }
        Long routeVersionId = confirmedRoute.get().getCurrentVersionId();
        Optional<RunStop> myStop = runStopRepository.findByRouteVersionIdAndStopId(routeVersionId, stopId);
        if (myStop.isEmpty()) {
            return;
        }
        if (myStop.get().getDepartedAt() != null) {
            throw new BusinessException(ErrorCode.STOP_ALREADY_DEPARTED);
        }
    }

    /**
     * FEATURE_SPEC §3.3 전이 표를 강제한다(Ruling 345, BR-031 1번 갈래) — 허용은
     * {@code waiting→boarded} · {@code waiting→no_show} · {@code boarded→alighted} 셋뿐이다.
     * 같은 상태 재요청을 포함해 그 밖은 전부 {@code 409 RIDER_TRANSITION_NOT_ALLOWED} — 표 밖으로
     * 가려면 되돌리기(§4.7)가 먼저다. 이 검사는 <b>PATCH 경로에만</b> 걸린다 — 되돌리기(revertTo)·
     * 하원 자동 승차(autoBoard)·미등원(markAbsent)·이동(markRemoved)은 이 메서드를 거치지 않는다.
     */
    private void applyTransition(RunRider rider, RiderStatus fromStatus, RiderStatus targetStatus,
            OffsetDateTime now) {
        boolean allowed = (fromStatus == RiderStatus.WAITING && targetStatus == RiderStatus.BOARDED)
                || (fromStatus == RiderStatus.WAITING && targetStatus == RiderStatus.NO_SHOW)
                || (fromStatus == RiderStatus.BOARDED && targetStatus == RiderStatus.ALIGHTED);
        if (!allowed) {
            throw new BusinessException(ErrorCode.RIDER_TRANSITION_NOT_ALLOWED);
        }
        switch (targetStatus) {
            case BOARDED -> rider.board(now);
            case ALIGHTED -> rider.alight(now);
            case NO_SHOW -> rider.markNoShow(now);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 하원 자동 종료 경계(목표 10, T2 소유) — 방금 처리한 하차·미승차(BR-031)가 그 회차의 마지막 잔여 탑승자였는지를
     * {@link RunCompletionService} 에 위임해 묻고, 그 호출로 실제 종료됐을 때만
     * {@link RunEndedEvent} 를 발행한다. 반환값을 무시하고 매번(또는 전혀) 발행하면 알림이 잔류자가
     * 있는 회차에도 나가거나 정작 종료된 회차에서 나가지 않는 결함이 된다 — 이 분기 자체가 T3 이
     * 검사해야 할 갈래다(전이 로직은 여기서 구현하지 않는다).
     */
    private void notifyIfRunJustEnded(Run run, OffsetDateTime now) {
        boolean justEnded = runCompletionService.completeIfAllAlighted(run, now);
        if (justEnded) {
            // 마지막 승하차지 강제 출발(Ruling 312, 목표 8) — 이 시점의 최종 지점은 다음 정차지가
            // 없어 RunArrivalCommandService 의 폴백(목표 7)을 받지 못한다.
            stopDepartureService.forceAllRemaining(run.getId(), run.getAcademyId(), now);
            long autoAlightedCount = runRiderRepository.countByRunIdAndStatus(run.getId(), RiderStatus.ALIGHTED);
            eventPublisher.publishEvent(new RunEndedEvent(run.getId(), run.getAcademyId(), now, autoAlightedCount));
        }
    }

    /**
     * 미승차 케이스 생성(목표 7) — 학원별 대기 시간(목표 2) 뒤 만료로 저장하고, 학부모·관계자 두 갈래
     * 알림 + WebSocket {@code rider_changed} 방송(Phase 10 이월 ⑤, 이 클래스 상단 참고) 두 갈래 재료가
     * 될 {@link RiderNoShowEvent} 를 발행한다.
     *
     * <p>{@code stop_skipped}(API_SPEC §4.6, C-05)는 {@link #skipStopIfNoRidersRemain} 이 실제로
     * 계산한다 — C-05 가 명시하는 두 발생 경로(③구간 미등원·운행 중 미승차) 중 여기가 후자다. 이벤트
     * 발행을 그 계산 <b>뒤로</b> 옮겼다(Phase 11) — {@code rider_changed} 방송이 그 값을 실어야 하는데,
     * 계산 전에 발행하면 항상 {@code false} 를 실어 보내게 된다.
     */
    private RiderStatusUpdateResponse handleNoShow(Run run, RunRider rider, OffsetDateTime now) {
        NoShowCase noShowCase = openNoShowCase(run, rider, now);

        boolean stopSkipped = skipStopIfNoRidersRemain(run.getId(), rider.getStopId());
        eventPublisher.publishEvent(new RiderNoShowEvent(run.getId(), run.getAcademyId(), rider.getStudentId(),
                rider.getId(), noShowCase.getId(), rider.getStopId(), stopSkipped, now));

        RiderStatusUpdateResponse.NoShowCaseSummary summary = new RiderStatusUpdateResponse.NoShowCaseSummary(
                noShowCase.getId(), noShowCase.getStartedAt(), noShowCase.getExpiresAt());
        return RiderStatusUpdateResponse.withNoShowCase(rider.getId(), now, summary, stopSkipped);
    }

    /**
     * 미승차 대기 카운트다운을 시작한다 — 학원별 대기 시간(목표 2) 뒤 만료. 탑승자당 케이스는 1개
     * ({@code uk_no_show_case_run_rider})라, 되돌린 뒤 다시 미승차면 기존 케이스를 재개한다(BR-009).
     */
    private NoShowCase openNoShowCase(Run run, RunRider rider, OffsetDateTime now) {
        int waitMinutes = academySettingRepository.findById(run.getAcademyId())
                .map(AcademySetting::getNoShowWaitMinutes)
                .orElse(AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES);
        OffsetDateTime expiresAt = now.plus(Duration.ofMinutes(waitMinutes));
        return noShowCaseRepository.findByRunRiderId(rider.getId())
                .map(existing -> {
                    existing.reopen(now, expiresAt);
                    return existing;
                })
                .orElseGet(() -> noShowCaseRepository.save(NoShowCase.forRunRider(rider.getId(), now, expiresAt, now)));
    }

    /**
     * 되돌리기가 {@code no_show} 를 드나들 때 미승차의 부수 효과를 맞춘다(BR-009). 벗어나면 케이스를
     * 종결하고(에스컬레이션 대상에서 빠짐) 그 승하차지의 미정차 표시를 푼다 — 이 탑승자가 다시 잔여로
     * 세어지기 때문이다. 되돌린 결과가 {@code no_show} 면({@code no_show → waiting → no_show}, Ruling 305
     * 반복) {@link #handleNoShow} 와 같은 상태를 만든다. 알림 이벤트는 되돌리기 쪽 규칙(Ruling 308)을
     * 따라 새로 내지 않는다.
     */
    private void syncNoShowAfterRevert(Run run, RunRider rider, RiderStatus fromStatus, RiderStatus toStatus,
            OffsetDateTime now) {
        if (fromStatus == RiderStatus.NO_SHOW && toStatus != RiderStatus.NO_SHOW) {
            noShowCaseRepository.findByRunRiderId(rider.getId()).ifPresent(noShowCase -> noShowCase.resolveByRevert(now));
            currentRunStop(run.getId(), rider.getStopId()).ifPresent(RunStop::clearSkipped);
        } else if (toStatus == RiderStatus.NO_SHOW && fromStatus != RiderStatus.NO_SHOW) {
            openNoShowCase(run, rider, now);
            skipStopIfNoRidersRemain(run.getId(), rider.getStopId());
        }
    }

    /** 확정 노선 현재 버전에서 그 승하차지의 정차 항목 — 확정 노선이 아직 없으면 비어 있다. */
    private Optional<RunStop> currentRunStop(Long runId, Long stopId) {
        return confirmedRouteRepository.findById(runId)
                .map(ConfirmedRoute::getCurrentVersionId)
                .flatMap(versionId -> runStopRepository.findByRouteVersionIdAndStopId(versionId, stopId));
    }

    /**
     * 그 승하차지에 아직 남은(부재·미승차 둘 다 빠진) 탑승자가 0명이면 확정 노선의 정차 항목을
     * {@code skipped} 로 표시하고 {@code true} 를 돌려준다(C-05 후자 경로, §4.6 {@code stop_skipped}).
     *
     * <p>{@code no_show} 도 잔여 판정에서 빼야 한다(목표 7) — 방금 이 호출 직전에 {@code applyTransition}
     * 이 이 탑승자 자신을 {@code NO_SHOW} 로 이미 바꿔 놓은 상태라, {@code ABSENT} 만 빼면 그 탑승자
     * 자신이 스스로를 "남은 사람"으로 세어 잔여가 영원히 0이 되지 않는다.
     * {@link src.backend.request.command.BoardingIntentCommandService#skipStopIfNoRidersRemain} 이
     * 확정 노선을 찾는 것과 같은 경로를 그대로 재사용하되, 제외 상태 집합만 이 경로에 맞게 넓힌
     * {@link RunRiderRepository#countByRunIdAndStopIdAndStatusNotIn} 을 쓴다.
     */
    private boolean skipStopIfNoRidersRemain(Long runId, Long stopId) {
        long remaining = runRiderRepository.countByRunIdAndStopIdAndStatusNotIn(runId, stopId, NOT_REMAINING);
        if (remaining > 0) {
            return false;
        }
        return currentRunStop(runId, stopId)
                .map(runStop -> {
                    runStop.markSkipped(SKIP_NOTICE);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 재생 대상이 이 요청과 같은 처리인지 대조한다(BR-078) — {@code findByClientKey} 는 학원 범위 밖 조회라
     * 대조 책임이 서비스에 있다({@code RiderStatusHistoryRepository} 예외 사유). 같은 회차의 같은 탑승자·같은
     * 상태가 아니면 키가 다른 처리에 재사용된 것이라, 남의 결과를 돌려주고 새 처리를 버리는 대신 422 로 거절한다.
     */
    private RiderStatusHistory assertSameRequest(RiderStatusHistory history, Long runId, Long riderId,
            String status) {
        boolean sameRequest = history.getRunRiderId().equals(riderId)
                && history.getToStatus() == parseTargetStatus(status)
                && runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT).isPresent();
        if (!sameRequest) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return history;
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
