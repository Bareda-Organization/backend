package src.backend.request.command;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.BoardingIntentToggleResponse;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.student.entity.Student;

/** 탑승 의사 토글의 ②구간(승인 필요) 처리 단계 — 호출하는 토글 트랜잭션 안에서 실행된다. */
@Component
@RequiredArgsConstructor
class BoardingIntentApprovalStep {

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final BoardingIntentStatusReader statusReader;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * ②구간 — 끄기만 승인 대기 큐에 {@link ChangeRequestType#CANCEL} 로 올린다(BR-029). 켜기는
     * {@code 403 CHANGE_WINDOW_CLOSED} — 30분 안쪽에는 추가 불가, 취소만 승인 경로다(PRD "오늘만 다른
     * 승하차지"). 현재 의사와 같은 값은 한도·요청 없이 무변경 {@code applied} 로 답한다.
     *
     * <p>응답의 {@code riding} 은 <b>바뀌지 않은 기존 값</b>이다(§3.6) — 실제 반영은 승인 이후다.
     */
    BoardingIntentToggleResponse requestApproval(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        if (riding == intent.isRiding()) {
            return BoardingIntentToggleResponse.applied(riding,
                    statusReader.riderStatusOf(run.getId(), student.getId(), riding),
                    BoardingIntentStatusReader.quotaLeftOf(intent));
        }
        if (riding) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        ChangeRequestStore.consumeChangeQuota(boardingIntentRepository, intent);
        ChangeRequest changeRequest = saveCancelRequest(run, student, requester, now);
        eventPublisher.publishEvent(new ApprovalRequestedEvent(changeRequest.getId(), run.getAcademyId(),
                run.getId(), student.getId(), now));

        boolean existingRiding = intent.isRiding();
        String riderStatus = statusReader.riderStatusOf(run.getId(), student.getId(), existingRiding);
        return BoardingIntentToggleResponse.pendingApproval(existingRiding, riderStatus, changeRequest.getId(),
                BoardingIntentStatusReader.quotaLeftOf(intent), ChangeWindowPolicy.deadlineOf(run));
    }

    /** 취소 변경 요청을 만들어 마감 시각을 붙이고 저장한다. */
    private ChangeRequest saveCancelRequest(Run run, Student student, AuthUser requester, OffsetDateTime now) {
        ChangeRequest changeRequest = ChangeRequest.forRequest(run.getAcademyId(), run.getId(), student.getId(),
                ChangeRequestSource.INTENT, ChangeRequestType.CANCEL, ChangeWindow.APPROVAL_REQUIRED.code(),
                requester.accountId(), now);
        changeRequest.assignDeadline(ChangeWindowPolicy.deadlineOf(run));
        return changeRequestRepository.save(changeRequest);
    }
}
