package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.command.StopMatcher;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 검증을 마친 변경 신청을 구간별로 반영한다(P-06, API_SPEC §3.8).
 *
 * <p><b>이 클래스가 트랜잭션 경계이고, 여기에는 {@code GeocodingClient} 가 없다</b> — 외부 호출(주소
 * 검증)은 {@code ChangeRequestCommandService} 가 트랜잭션 밖에서 끝낸 뒤 결과({@link GeocodedPoint})만
 * 넘어온다({@code WeeklyAddressStore} 와 같은 형태, §7 규칙 16).
 *
 * <p>①구간에서 재최적화·확정 배치를 부르지 않는다(Ruling 198) — 이 시점엔 회차가 아직 idle 이라
 * 확정된 노선이 부재하다. 나중에 도래하는 확정 배치({@code RunConfirmationService.confirmOne})가
 * 이 change_request 를 읽어 그날의 승하차지에 반영한다.
 */
@Component
@RequiredArgsConstructor
public class ChangeRequestStore {

    private final ChangeRequestRepository changeRequestRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final StopMatcher stopMatcher;

    private final ApplicationEventPublisher eventPublisher;

    private final RunRepository runRepository;

    private final Clock clock;

    /** 경유지 이동 신청(§3.8 {@code type=relocate}) — 좌표를 승하차지에 매칭한 뒤 구간별로 반영한다. */
    @Transactional
    public ChangeRequest submitRelocate(Student student, Run run, Long requestedBy, String newAddress,
            GeocodedPoint point, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow window = windowAt(run, now);
        Long stopId = stopMatcher.matchOrCreate(student.getAcademyId(), point).getId();
        ChangeRequest changeRequest = create(student, run, window, ChangeRequestType.RELOCATE, requestedBy, reason,
                now);
        changeRequest.assignRelocationTarget(newAddress, point.lat(), point.lng(), stopId);
        return finish(changeRequest, student, run, window, requestedBy, now);
    }

    /** 탑승 취소 신청(§3.8 {@code type=cancel}) — ③구간은 이 메서드에 닿기 전에 컨트롤러 쪽에서 거절된다. */
    @Transactional
    public ChangeRequest submitCancel(Student student, Run run, Long requestedBy, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow window = windowAt(run, now);
        if (window == ChangeWindow.IMMEDIATE) {
            Optional<ChangeRequest> already = alreadyCanceled(student, run);
            if (already.isPresent()) {
                return already.get();
            }
        }
        ChangeRequest changeRequest = create(student, run, window, ChangeRequestType.CANCEL, requestedBy, reason,
                now);
        return finish(changeRequest, student, run, window, requestedBy, now);
    }

    /**
     * ①구간에서 이미 탑승 의사가 꺼져 있고 승인된 취소 신청이 있으면 그 신청을 돌려준다(BR-250) — 재전송(네트워크 재시도·
     * 더블탭)이 승인 행과 관계자 {@code absent} 통지를 그 횟수만큼 쌓지 않는다.
     */
    private Optional<ChangeRequest> alreadyCanceled(Student student, Run run) {
        boolean off = boardingIntentRepository.findByRunIdAndStudentId(run.getId(), student.getId())
                .filter(intent -> !intent.isRiding()).isPresent();
        if (!off) {
            return Optional.empty();
        }
        List<ChangeRequest> approved = changeRequestRepository
                .findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(student.getAcademyId(), run.getId(),
                        ChangeRequestType.CANCEL, ChangeRequestStatus.APPROVED);
        return approved.stream().filter(cr -> cr.getStudentId().equals(student.getId()))
                .reduce((first, last) -> last);
    }

    /**
     * 구간을 <b>저장 시점</b>에 다시 판정한다(BR-074) — 주소 검증을 트랜잭션 밖에서 기다리는 동안 확정 시각이
     * 지나거나 운행이 시작될 수 있어, 앞서 판정한 구간으로 저장하면 ①로 "즉시 반영" 이라 답하면서 확정
     * 노선에는 빠진다. 회차 상태도 다시 읽는다.
     */
    private ChangeWindow windowAt(Run run, OffsetDateTime now) {
        Run fresh = runRepository.findByIdAndAcademyId(run.getId(), run.getAcademyId()).orElseThrow(
                () -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        ChangeWindowPolicy.assertNotCanceled(fresh);
        ChangeWindow window = ChangeWindowPolicy.segmentOf(fresh, now);
        if (window == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        return window;
    }

    private ChangeRequest create(Student student, Run run, ChangeWindow window, ChangeRequestType type,
            Long requestedBy, String reason, OffsetDateTime now) {
        ChangeRequest changeRequest = ChangeRequest.forRequest(student.getAcademyId(), run.getId(), student.getId(),
                ChangeRequestSource.CHANGE_REQUEST, type, window.code(), requestedBy, now);
        if (reason != null) {
            changeRequest.assignReason(reason);
        }
        return changeRequest;
    }

    private ChangeRequest finish(ChangeRequest changeRequest, Student student, Run run, ChangeWindow window,
            Long requestedBy, OffsetDateTime now) {
        if (window == ChangeWindow.IMMEDIATE) {
            applyImmediate(changeRequest, student, run, requestedBy, now);
        } else {
            applyApprovalRequired(changeRequest, student, run, now);
        }
        ChangeRequest saved = changeRequestRepository.save(changeRequest);
        if (window == ChangeWindow.APPROVAL_REQUIRED) {
            // save() 뒤에 publishEvent 를 부르는 이유 — IDENTITY 채번이라 save() 가 반환한 뒤에야
            // saved.getId() 가 채워진다. 리스너가 그 id 로 알림 로그를 남긴다.
            eventPublisher.publishEvent(new ApprovalRequestedEvent(saved.getId(), run.getAcademyId(), run.getId(),
                    student.getId(), now));
        }
        return saved;
    }

    /**
     * ①구간 — 승인 없이 즉시 반영한다. CANCEL 은 탑승 의사까지 바로 끄지만, RELOCATE 는 change_request
     * 행에 목표 승하차지를 남기는 것으로 끝난다 — 명단에는 영향이 없다(경유지 선택만 바뀐다).
     */
    private void applyImmediate(ChangeRequest changeRequest, Student student, Run run, Long requestedBy,
            OffsetDateTime now) {
        changeRequest.approve(null, now, null, null);
        if (changeRequest.getType() == ChangeRequestType.CANCEL) {
            BoardingIntent boardingIntent = findOrCreateIntent(run.getId(), student.getId(), now);
            boardingIntent.applyRiding(false, ChangeWindow.IMMEDIATE, now, requestedBy);
            boardingIntentRepository.save(boardingIntent);
            // 관계자 통지(§9.7 absent) — 이 경로엔 intent_changed 가 없다(BR-110)
            eventPublisher.publishEvent(new AbsentRecordedEvent(run.getId(), run.getAcademyId(), student.getId(),
                    now));
        }
    }

    /**
     * ②구간 — 대기 상태로 접수하고 회차당 1회 한도를 소비한다(목표 5). RELOCATE·CANCEL 이 같은 큐·같은
     * 한도를 공유한다 — 실제 반영은 관리자 승인(T5 소유) 이후다.
     */
    private void applyApprovalRequired(ChangeRequest changeRequest, Student student, Run run, OffsetDateTime now) {
        BoardingIntent boardingIntent = findOrCreateIntent(run.getId(), student.getId(), now);
        consumeChangeQuota(boardingIntentRepository, boardingIntent);
        changeRequest.assignDeadline(run.getDepartTime());
    }

    private BoardingIntent findOrCreateIntent(Long runId, Long studentId, OffsetDateTime now) {
        boardingIntentRepository.insertIfAbsent(runId, studentId, now);
        return boardingIntentRepository.findByRunIdAndStudentId(runId, studentId).orElseThrow();
    }

    /**
     * ②구간 한도 1회 소비 — 조건부 UPDATE 가 판정하고(동시 요청 중 하나만 1행), 성공하면 메모리 값을 맞춘다
     * (BR-027). 탑승 의사 토글({@link BoardingIntentCommandService})과 같은 한도를 공유해 같은 절차를 쓴다.
     */
    static void consumeChangeQuota(BoardingIntentRepository repository, BoardingIntent intent) {
        if (repository.claimChangeQuota(intent.getId()) == 0) {
            throw new BusinessException(ErrorCode.CHANGE_LIMIT_REACHED);
        }
        intent.consumeChangeQuota();
    }
}
