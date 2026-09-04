package src.backend.run.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지연 알림 발신 이력 1행(NTF-06 · M-05, API_SPEC §4.9, ERD §3.4) — 발신 1회 = 이 테이블 1행.
 *
 * <p>{@code notification_log} 와 별개인 이유는 그쪽이 발송 <b>종류·수신자별</b>로 여러 행이 나뉘어
 * 적재되기 때문이다(아웃박스 관례) — Ruling 253 의 중복·갱신 판정은 "이 회차의 마지막 지연 신고
 * 1건" 단위라 그 조회에 맞는 전용 테이블을 둔다({@link src.backend.run.repository.DelayNoticeRepository}
 * 참고).
 */
@Entity
@Table(name = "delay_notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DelayNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "sent_by_account_id", nullable = false)
    private Long sentByAccountId;

    @Column(name = "minutes", nullable = false)
    private int minutes;

    @Convert(converter = DelayReason.Db.class)
    @Column(name = "reason", length = 20, nullable = false)
    private DelayReason reason;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    private DelayNotice(Long runId, Long sentByAccountId, int minutes, DelayReason reason, String message,
            OffsetDateTime sentAt) {
        this.runId = runId;
        this.sentByAccountId = sentByAccountId;
        this.minutes = minutes;
        this.reason = reason;
        this.message = message;
        this.sentAt = sentAt;
    }

    /** 동승자가 지연을 신고해 알림이 실제로 발신될 때 생성한다 — 중복 판정(409)에 걸린 요청은 이 팩토리를 타지 않는다. */
    public static DelayNotice onSend(Long runId, Long sentByAccountId, int minutes, DelayReason reason,
            String message, OffsetDateTime sentAt) {
        return new DelayNotice(runId, sentByAccountId, minutes, reason, message, sentAt);
    }

    /**
     * 이번 신고가 이 행과 완전히 같은 내용인지(Ruling 253) — {@code minutes}·{@code reason}·
     * {@code message} 전부가 같아야 한다. {@code message} 는 {@code null} 끼리도 같은 것으로 본다
     * (둘 다 자동 생성 문구를 썼다는 뜻이라 실질적으로 같은 신고다).
     */
    public boolean isSameContent(int minutes, DelayReason reason, String message) {
        return this.minutes == minutes && this.reason == reason
                && (this.message == null ? message == null : this.message.equals(message));
    }
}
