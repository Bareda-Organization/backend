package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.StopSkipJudge;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.BoardingIntentToggleRequest;
import src.backend.request.dto.BoardingIntentToggleResponse;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.entity.Student;

/**
 * 회차별 탑승 의사 토글(ATT-01·02·P-03, API_SPEC §3.6) — 3구간 판정({@link ChangeWindowPolicy})에
 * 따라 처리 경로가 완전히 갈린다.
 *
 * <p>{@code @Transactional} 을 붙인다 — {@code WeeklyAddressCommandService} 와 달리 이 메서드는
 * 외부 API 호출이 없고(①·③은 {@code RouteComputationPipeline} 을 부르지 않는다, 목표 1), "서버
 * 처리 실패 시 기존 상태 복구 + 횟수 미소진"(C-10)을 지키려면 여러 엔티티 변경이 한 트랜잭션으로
 * 묶여야 한다. {@code NotificationOutbox#append} 가 {@code Propagation.MANDATORY} 인 것도 이
 * 메서드가 열린 트랜잭션 안에서 호출된다는 전제다 — 그 알림 적재는 {@link IntentChangedEvent}
 * 발행을 구독한 알림 모듈 리스너가 대신하며, 이 클래스는 도메인 이벤트만 던지고 알림 모듈을 직접
 * 부르지 않는다(§7 규칙 17).
 *
 * <p><b>BR-101 — CODE_CONVENTIONS §20.2 클래스 200줄 신호를 넘긴 채 두는 이유</b>: ①·②·③ 세 구간이 서로 배타적인
 * 처리 경로라 이미 {@code applyImmediate}·{@code requestApproval}·{@code applyClosed} 사설
 * 메서드로 나뉘어 있다 — 그 셋을 각각 20줄 안팎으로 유지하는 것이 "탑승 의사 토글" 이라는 한 진입점
 * 아래 있어야 세 구간의 분기 기준(API_SPEC §3.6)을 한눈에 비교할 수 있다. 별도 클래스로 쪼개면 그 비교가
 * 파일을 오가야 하는 일이 된다.
 */
@Service
@RequiredArgsConstructor
public class BoardingIntentCommandService {

    /** ③구간 전원 미등원으로 건너뛴 정차지에 남기는 사유(API_SPEC §3.6 ③ · {@code run_stop.skip_notice}). */
    private static final String SKIP_NOTICE = "탑승 의사 토글로 전원 미탑승";

    private final LinkedChildLookup linkedChildLookup;

    private final TargetRunLookup targetRunLookup;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopSkipJudge stopSkipJudge;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 자녀·회차를 확인하고 3구간 중 하나로 처리한다.
     *
     * <p>회차는 {@link TargetRunLookup} 이 학원 범위와 "그 자녀의 대상 회차인가" 를 함께 판정해 얻는다 —
     * 다른 학원의 회차·대상이 아닌 회차는 모두 {@code 404 RUN_NOT_FOUND}(§1.11, Ruling 163 · BR-084).
     * 이 조회가 성공한 뒤에는 {@code run.academyId} 가 {@code student.academyId} 와 같음이 보장되므로,
     * 그 뒤의 {@link BoardingIntentRepository#findByRunIdAndStudentId}(부모 경유·{@code AcademyScopeExempt})
     * 가 전제하는 "호출부가 이미 학원 소속을 확인했다" 를 이 시점에 만족한다.
     */
    @Transactional
    public BoardingIntentToggleResponse toggle(AuthUser requester, Long studentId, Long runId,
            BoardingIntentToggleRequest request) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        Run run = targetRunLookup.targetRun(student, runId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow segment = ChangeWindowPolicy.segmentOf(run, now);

        boardingIntentRepository.insertIfAbsent(run.getId(), student.getId(), now);
        BoardingIntent intent = boardingIntentRepository.findByRunIdAndStudentId(run.getId(), student.getId())
                .orElseThrow();

        return switch (segment) {
            case IMMEDIATE -> applyImmediate(run, student, intent, request.riding(), requester, now);
            case APPROVAL_REQUIRED -> requestApproval(run, student, intent, request.riding(), requester, now);
            case CLOSED -> applyClosed(run, student, intent, request.riding(), requester, now);
        };
    }

    /**
     * ①구간 — 승인 없이 즉시 반영한다. {@link src.backend.routing.pipeline.RouteComputationPipeline}
     * 을 부르지 않는다(Ruling 198) — 이 회차는 아직 {@code idle} 이라 {@code confirmed_route} 자체가
     * 없고, 방금 바뀐 {@code riding} 값은 확정 배치가 나중에 명단을 만들 때 비로소 읽힌다(목표 1,
     * {@code RunConfirmationService} 의 제외 목록 필터).
     */
    private BoardingIntentToggleResponse applyImmediate(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        if (riding == intent.isRiding()) {
            // BR-250 — 같은 값의 재전송은 무변경 응답이다. 이벤트를 내면 관계자 푸시가 그 횟수만큼 간다.
            return BoardingIntentToggleResponse.applied(riding, riderStatusOf(run.getId(), student.getId(), riding),
                    quotaLeftOf(intent));
        }
        intent.applyRiding(riding, ChangeWindow.IMMEDIATE, now, requester.accountId());
        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), riding, now));
        String riderStatus = riderStatusOf(run.getId(), student.getId(), riding);
        return BoardingIntentToggleResponse.applied(riding, riderStatus, quotaLeftOf(intent));
    }

    /**
     * ②구간 — 끄기만 승인 대기 큐에 {@link ChangeRequestType#CANCEL} 로 올린다(BR-029). 켜기는
     * {@code 403 CHANGE_WINDOW_CLOSED} — 30분 안쪽에는 추가 불가, 취소만 승인 경로다(PRD "오늘만 다른
     * 승하차지"). 현재 의사와 같은 값은 한도·요청 없이 무변경 {@code applied} 로 답한다.
     *
     * <p>응답의 {@code riding} 은 <b>바뀌지 않은 기존 값</b>이다(§3.6) — 실제 반영은 승인 이후다.
     */
    private BoardingIntentToggleResponse requestApproval(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        if (riding == intent.isRiding()) {
            return BoardingIntentToggleResponse.applied(riding, riderStatusOf(run.getId(), student.getId(), riding),
                    quotaLeftOf(intent));
        }
        if (riding) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        ChangeRequestStore.consumeChangeQuota(boardingIntentRepository, intent);

        ChangeRequest changeRequest = ChangeRequest.forRequest(run.getAcademyId(), run.getId(), student.getId(),
                ChangeRequestSource.INTENT, ChangeRequestType.CANCEL, ChangeWindow.APPROVAL_REQUIRED.code(),
                requester.accountId(), now);
        changeRequest.assignDeadline(run.getDepartTime());
        changeRequest = changeRequestRepository.save(changeRequest);

        eventPublisher.publishEvent(new ApprovalRequestedEvent(changeRequest.getId(), run.getAcademyId(),
                run.getId(), student.getId(), now));

        boolean existingRiding = intent.isRiding();
        String riderStatus = riderStatusOf(run.getId(), student.getId(), existingRiding);
        return BoardingIntentToggleResponse.pendingApproval(existingRiding, riderStatus, changeRequest.getId(),
                quotaLeftOf(intent), run.getDepartTime());
    }

    /**
     * ③구간 — 운행 시작 후(또는 출발 시각 도달)라 재최적화 없이 미등원만 즉시 수용한다.
     * {@code riding=true}(되돌리기 시도)와 아직 타지 않은({@code waiting}) 학생이 아닌 경우는
     * {@code 403 CHANGE_WINDOW_CLOSED} 다(§3.6 ③, Ruling 334) — 이미 탄 학생을 {@code absent} 로
     * 덮으면 종료 판정이 그 아이를 잔류로 세지 않는다.
     *
     * <p>반영되면 기사·동승자에게 전달한다 — {@link RiderStatusChangedEvent}(WS {@code rider_changed})
     * 와 {@link RunRouteConfirmedEvent}(기존 {@code route_changed} 알림, 해당 승하차지 미정차).
     *
     * <p>{@code run_rider} 행이 없으면(그 학생이 애초에 이 회차 명단에 없을 때 — 이미 ①구간에서
     * {@code riding=false} 로 확정 배치의 제외 목록에 걸렸던 경우가 대표적이다) 부재 표시·정차지
     * 재계산·기사 전달을 조용히 건너뛴다 — 이미 명단에 없는 학생을 다시 없앨 대상이 없다.
     *
     * <p>③구간은 동승자의 승하차 처리와 겹치는 때라 탑승자 행을 승하차 경로(BR-267)와 같은 행 잠금으로 읽는다(BR-303) —
     * 승차가 먼저 커밋됐으면 {@code boarded} 를 읽어 403 이다. "잔여 0명이면 정차 건너뜀" 판정은 승하차 경로와 같은
     * {@link StopSkipJudge} 가 정차 항목을 잠근 뒤 세고 {@code no_show} 도 잔여에서 뺀다(BR-325).
     */
    private BoardingIntentToggleResponse applyClosed(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        // BR-303 — 탑승자 행을 잠근 채 읽는다: 방금 승차가 커밋됐다면 boarded 를 읽어 아래에서 거절되고, absent 로 덮지 않는다.
        Optional<RunRider> rider = runRiderRepository.findLockedByRunIdAndStudentId(run.getId(), student.getId());
        if (riding || rider.filter(r -> r.getStatus() != RiderStatus.WAITING).isPresent()) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        intent.applyRiding(false, ChangeWindow.CLOSED, now, requester.accountId());

        rider.ifPresent(r -> {
            r.markAbsent(now);
            stopSkipJudge.skipIfNoRidersRemain(run.getId(), r.getStopId(), SKIP_NOTICE);
            eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                    student.getId(), r.getId(), statusNameOf(RiderStatus.ABSENT), now, false));
            eventPublisher.publishEvent(
                    new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
        });

        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), false, now));
        String riderStatus = rider.map(r -> statusNameOf(r.getStatus())).orElse(statusNameOf(RiderStatus.ABSENT));
        return BoardingIntentToggleResponse.appliedNoReroute(false, riderStatus, quotaLeftOf(intent));
    }

    /**
     * 응답의 {@code rider_status} — 실제 {@code run_rider} 행이 있으면 그 상태를, 아직 없으면(①구간은
     * 확정 전이라 항상 이 경우다) {@code riding} 으로부터 합성한다. API_SPEC §3.6 이 명시하는 규칙은
     * "{@code applied} + {@code riding=false} → {@code absent}" 하나뿐이라, 나머지 조합은 이 합성으로
     * 일관되게 채운다.
     */
    private String riderStatusOf(Long runId, Long studentId, boolean riding) {
        return runRiderRepository.findByRunIdAndStudentId(runId, studentId)
                .map(r -> statusNameOf(r.getStatus()))
                .orElse(riding ? statusNameOf(RiderStatus.WAITING) : statusNameOf(RiderStatus.ABSENT));
    }

    private static String statusNameOf(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** ②구간 변경 한도 잔여 — {@link BoardingIntent#hasChangeQuota} 를 응답 계약의 정수 형태로 옮긴다. */
    private static int quotaLeftOf(BoardingIntent intent) {
        return intent.hasChangeQuota() ? 1 : 0;
    }
}
