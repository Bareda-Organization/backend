package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
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
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.entity.Student;

/**
 * 회차별 탑승 의사 토글(ATT-01·02·P-03, API_SPEC §3.6) — 3구간 판정({@link ChangeWindowPolicy})에
 * 따라 처리 경로가 완전히 갈린다.
 *
 * <p>{@code @Transactional} 을 붙인다 — {@code WeeklyAddressCommandService} 와 달리 이 메서드는
 * 외부 API 호출이 없고(①·③은 {@code RouteComputationPipeline} 을 부르지 않는다, 목표 1), "서버
 * 처리 실패 시 기존 상태 복구 + 횟수 미소진"(C-10)을 지키려면 여러 엔티티 변경이 한 트랜잭션으로
 * 묶여야 한다. {@link src.backend.notification.command.NotificationOutbox#append} 가
 * {@code Propagation.MANDATORY} 인 것도 이 메서드가 열린 트랜잭션 안에서 호출된다는 전제다.
 */
@Service
@RequiredArgsConstructor
public class BoardingIntentCommandService {

    /** ③구간 전원 미등원으로 건너뛴 정차지에 남기는 사유(API_SPEC §3.6 ③ · {@code run_stop.skip_notice}). */
    private static final String SKIP_NOTICE = "탑승 의사 토글로 전원 미탑승";

    private final LinkedChildLookup linkedChildLookup;

    private final RunRepository runRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 자녀·회차를 확인하고 3구간 중 하나로 처리한다.
     *
     * <p>회차 조회는 {@link RunRepository#findByIdAndAcademyId} 로 학원 범위에 직접 좁혀 얻는다 —
     * 별도로 {@code AcademyScope.assertAccessible} 를 부르지 않는다. 다른 학원의 회차를 지목하면
     * 이 조회가 곧바로 빈 결과가 되어 {@code 404 RUN_NOT_FOUND} 로 답하는데, 이는 API_SPEC §3.6 의
     * 에러 표에 {@code ACADEMY_SCOPE_VIOLATION} 이 없고 대신 같은 {@code {id}} 지목 자원인
     * {@code SCHEDULE_NOT_FOUND}·{@code ROUTE_NOT_FOUND} 가 "없음"과 "남의 학원"을 같은 404 로
     * 답하는 관례(Ruling 153·180)를 따른 것이다. 이 조회가 성공한 뒤에는 {@code run.academyId} 가
     * {@code student.academyId} 와 같음이 보장되므로, 그 뒤의 {@link BoardingIntentRepository
     * #findByRunIdAndStudentId}(부모 경유·{@code AcademyScopeExempt}) 가 전제하는 "호출부가 이미
     * 학원 소속을 확인했다" 를 이 시점에 만족한다.
     */
    @Transactional
    public BoardingIntentToggleResponse toggle(AuthUser requester, Long studentId, Long runId,
            BoardingIntentToggleRequest request) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        Run run = runRepository.findByIdAndAcademyId(runId, student.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow segment = ChangeWindowPolicy.segmentOf(run, now);

        BoardingIntent intent = boardingIntentRepository.findByRunIdAndStudentId(run.getId(), student.getId())
                .orElseGet(() -> boardingIntentRepository
                        .save(BoardingIntent.forRun(run.getId(), student.getId(), now)));

        return switch (segment) {
            case IMMEDIATE -> applyImmediate(run, student, intent, request.riding(), requester, now);
            case APPROVAL_REQUIRED -> requestApproval(run, student, intent, requester, now);
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
        intent.applyRiding(riding, ChangeWindow.IMMEDIATE, now, requester.accountId());
        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), riding, now));
        String riderStatus = riderStatusOf(run.getId(), student.getId(), riding);
        return BoardingIntentToggleResponse.applied(riding, riderStatus, quotaLeftOf(intent));
    }

    /**
     * ②구간 — 즉시 반영하지 않고 승인 대기 큐에 올린다. {@code riding} 방향(켜기·끄기)을 구분해 저장할
     * 컬럼이 {@link ChangeRequest} 에 없어({@code type} 이 {@code RELOCATE}·{@code CANCEL} 둘뿐)
     * 항상 {@link ChangeRequestType#CANCEL} 로 접수한다 — 승인 단계(이 태스크 범위 밖)를 구현할 때
     * 반영 방향을 되짚을 근거가 이 요청 자체에 없다는 뜻이라 보고에 남긴다.
     *
     * <p>응답의 {@code riding} 은 <b>바뀌지 않은 기존 값</b>이다(§3.6) — 실제 반영은 승인 이후다.
     */
    private BoardingIntentToggleResponse requestApproval(Run run, Student student, BoardingIntent intent,
            AuthUser requester, OffsetDateTime now) {
        intent.consumeChangeQuota();

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
     * {@code riding=true}(되돌리기 시도)는 {@code 403 CHANGE_WINDOW_CLOSED} — 이미 배정된 순번을
     * 되살릴 수단이 이 구간에 없다(§3.6 ③).
     *
     * <p>{@code run_rider} 행이 없으면(그 학생이 애초에 이 회차 명단에 없을 때 — 이미 ①구간에서
     * {@code riding=false} 로 확정 배치의 제외 목록에 걸렸던 경우가 대표적이다) 부재 표시·정차지
     * 재계산을 조용히 건너뛴다 — API_SPEC 이 이 경우를 명시하지 않아 내린 판단이며, 이미 명단에
     * 없는 학생을 다시 없앨 대상이 없다는 것이 근거다.
     */
    private BoardingIntentToggleResponse applyClosed(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        if (riding) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        intent.applyRiding(false, ChangeWindow.CLOSED, now, requester.accountId());

        Optional<RunRider> rider = runRiderRepository.findByRunIdAndStudentId(run.getId(), student.getId());
        rider.ifPresent(r -> {
            r.markAbsent(now);
            skipStopIfNoRidersRemain(run.getId(), r.getStopId());
        });

        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), false, now));
        String riderStatus = rider.map(r -> statusNameOf(r.getStatus())).orElse(statusNameOf(RiderStatus.ABSENT));
        return BoardingIntentToggleResponse.appliedNoReroute(false, riderStatus, quotaLeftOf(intent));
    }

    /**
     * 그 정차지에 남은(부재 처리되지 않은) 탑승자가 0명이면 확정 노선의 정차 항목을
     * {@code skipped} 로 표시한다(§3.6 ③) — {@code seq} 는 손대지 않는다({@link
     * src.backend.routing.entity.RunStop#markSkipped} 자바독, C-05 순번 불변).
     */
    private void skipStopIfNoRidersRemain(Long runId, Long stopId) {
        long remaining = runRiderRepository.countByRunIdAndStopIdAndStatusNot(runId, stopId, RiderStatus.ABSENT);
        if (remaining > 0) {
            return;
        }
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return;
        }
        runStopRepository.findByRouteVersionIdAndStopId(confirmedRoute.get().getCurrentVersionId(), stopId)
                .ifPresent(runStop -> runStop.markSkipped(SKIP_NOTICE));
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
