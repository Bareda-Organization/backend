package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.event.NotificationAppended;
import src.backend.notification.repository.NotificationLogRepository;

/**
 * 알림을 아웃박스에 적재하는 <b>유일한 지점</b>(TECH_DECISIONS §7.2) — 상태 변경과 같은 트랜잭션에서
 * {@code push_state='pending'} 행을 남긴다.
 *
 * <p>{@code MANDATORY} 인 것이 이 클래스의 핵심이다. 자기 트랜잭션을 여는 순간 "상태 변경은
 * 롤백됐는데 알림만 남는" 창이 생기고, 그 알림은 <b>일어나지 않은 일</b>을 통지한다. 부를 자리가
 * 아니면 조용히 새 트랜잭션을 여는 대신 기동 실패에 준하는 예외로 알린다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutbox {

    private final NotificationLogRepository notificationLogRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 발송 멱등키를 강제하는 UNIQUE 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>제약을 이름으로 가리는 이유는 {@code DataIntegrityViolationException} 을 통째로 옮기면
     * <b>다른 제약 위반까지</b> "이미 적재됨" 으로 답해 원인을 감추기 때문이다 — 지금
     * {@code notification_log} 의 UNIQUE 가 이것 하나뿐인 것은 사정이지 근거가 아니다.
     */
    private static final String DEDUP_UNIQUE_CONSTRAINT = "uk_notification_log_dedup_key";

    /**
     * 발송 대기 행을 적재하고 커밋 후 즉시 발송을 예약한다.
     *
     * <p>{@link NotificationAppended} 를 여기서 발행하는 이유는 적재한 쪽만 행 식별자를 알기
     * 때문이다 — 리스너가 {@code dedup_key} 로 다시 찾게 하면 같은 키의 옛 행을 집을 수 있다.
     *
     * <p>{@code saveAndFlush} 로 <b>즉시</b> 내보내는 것이 중요하다. 커밋까지 미루면 제약 위반이
     * 아래 {@code catch} 밖에서 터지고, 그때는 이미 응답 변환 경로를 지나쳐 {@code 500} 이 나간다
     * ({@code AcademyStaffQuota} 가 같은 이유로 저장소 flush 를 부른다 — 예외 번역은
     * {@code @Repository} 빈을 거칠 때만 붙는다).
     *
     * <p>중복 판정을 "조회 후 저장" 으로 하지 않는 이유는 TECH_DECISIONS §9.2 다 — {@code exists}
     * 와 {@code save} 사이에 두 요청이 함께 통과하는 창이 남는다. <b>멱등은 DB 제약이 보장하고
     * 코드는 그 위반을 옮기기만 한다.</b>
     *
     * @return 적재된 행의 식별자
     * @throws BusinessException {@code DUPLICATE_NOTIFICATION} — 같은 {@code dedup_key} 가 이미 있다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long append(NotificationDraft draft) {
        try {
            NotificationLog appended = notificationLogRepository.saveAndFlush(NotificationLog.forOutbox(
                    draft.academyId(), draft.recipientAccountId(), draft.recipientName(),
                    draft.recipientRole(), draft.type(), draft.title(), draft.body(), draft.dedupKey(),
                    OffsetDateTime.now(clock)));
            eventPublisher.publishEvent(new NotificationAppended(appended.getId()));
            return appended.getId();
        } catch (DataIntegrityViolationException e) {
            if (isDedupViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_NOTIFICATION);
            }
            throw e;
        }
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 멱등키 제약인지만 본다. */
    private boolean isDedupViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && DEDUP_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }
}
