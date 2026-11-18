package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.BoardingIntentToggleRequest;
import src.backend.request.dto.BoardingIntentToggleResponse;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.run.command.RunRowLock;
import src.backend.run.entity.Run;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.entity.Student;

/**
 * 회차별 탑승 의사 토글(ATT-01·02·P-03, API_SPEC §3.6) — 3구간 판정({@link ChangeWindowPolicy})에
 * 따라 처리 경로가 완전히 갈린다.
 *
 * <p>{@code @Transactional} 을 붙인다 — {@code WeeklyAddressCommandService} 와 달리 이 메서드는
 * 외부 API 호출이 없고(①·③은 {@code RouteComputationPipeline} 을 부르지 않는다), "서버
 * 처리 실패 시 기존 상태 복구 + 횟수 미소진"(C-10)을 지키려면 여러 엔티티 변경이 한 트랜잭션으로
 * 묶여야 한다. {@code NotificationOutbox#append} 가 {@code Propagation.MANDATORY} 인 것도 이
 * 메서드가 열린 트랜잭션 안에서 호출된다는 전제다 — 그 알림 적재는 {@link IntentChangedEvent}
 * 발행을 구독한 알림 모듈 리스너가 대신하며, 이 클래스는 도메인 이벤트만 던지고 알림 모듈을 직접
 * 부르지 않는다(§7 규칙 17).
 */
@Service
@RequiredArgsConstructor
public class BoardingIntentCommandService {

    private final LinkedChildLookup linkedChildLookup;

    private final TargetRunLookup targetRunLookup;

    private final BoardingIntentRepository boardingIntentRepository;

    private final BoardingIntentStatusReader statusReader;

    private final BoardingIntentApprovalStep approvalStep;

    private final BoardingIntentClosedStep closedStep;

    /** 구간 판정과 탑승자 · 승하차지 행보다 먼저 쥐는 회차 행 잠금(승하차 기록 · 도착 처리와 같은 잠금 순서, {@link RunRowLock}). */
    private final RunRowLock runRowLock;

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

        // 구간을 판정하기 전에 회차 행을 쥐고 상태를 다시 읽는다 — 운행 시작과 겹쳐도 시작이 먼저면 마감 구간으로 판정되고, 요청이 먼저면
        // 시작이 그 대기 요청을 종결한다. 순서는 회차 → 의사 → 탑승자 → 승하차지다.
        runRowLock.acquire(run);
        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow segment = ChangeWindowPolicy.segmentOf(run, now);

        boardingIntentRepository.insertIfAbsent(run.getAcademyId(), run.getId(), student.getId(), now);
        // BR-361 — 행 잠금으로 읽는다: 더블탭의 두 번째 요청은 첫 요청이 커밋한 값을 읽어 "같은 값" 으로 판정된다
        BoardingIntent intent = boardingIntentRepository.findLockedByRunIdAndStudentId(run.getId(), student.getId())
                .orElseThrow();

        return switch (segment) {
            case IMMEDIATE -> applyImmediate(run, student, intent, request.riding(), requester, now);
            case APPROVAL_REQUIRED ->
                    approvalStep.requestApproval(run, student, intent, request.riding(), requester, now);
            case CLOSED -> closedStep.applyClosed(run, student, intent, request.riding(), requester, now);
        };
    }

    /**
     * ①구간 — 승인 없이 즉시 반영한다. {@link src.backend.routing.pipeline.RouteComputationPipeline}
     * 을 부르지 않는다(Ruling 198) — 이 회차는 아직 {@code idle} 이라 {@code confirmed_route} 자체가
     * 없고, 방금 바뀐 {@code riding} 값은 확정 배치가 나중에 명단을 만들 때 비로소 읽힌다
     * ({@code RunConfirmationService} 의 제외 목록 필터).
     */
    private BoardingIntentToggleResponse applyImmediate(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        if (riding == intent.isRiding()) {
            // BR-250 — 같은 값의 재전송은 무변경 응답이다. 이벤트를 내면 관계자 푸시가 그 횟수만큼 간다.
            return BoardingIntentToggleResponse.applied(riding,
                    statusReader.riderStatusOf(run.getId(), student.getId(), riding),
                    BoardingIntentStatusReader.quotaLeftOf(intent));
        }
        intent.applyRiding(riding, ChangeWindow.IMMEDIATE, now, requester.accountId());
        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), riding, now));
        String riderStatus = statusReader.riderStatusOf(run.getId(), student.getId(), riding);
        return BoardingIntentToggleResponse.applied(riding, riderStatus,
                BoardingIntentStatusReader.quotaLeftOf(intent));
    }
}
