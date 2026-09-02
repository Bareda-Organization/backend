package src.backend.notification.entity;

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

import src.backend.global.common.enums.Role;

/**
 * 알림 로그 — 발송 사실의 근거이자 트랜잭셔널 아웃박스이며, 상태 변경과 같은 트랜잭션에서 {@code pending}
 * 행을 남긴다(ERD §3.6 · ARCHITECTURE §7).
 *
 * <p>{@code academy_id}·{@code recipient_account_id}·{@code student_id}·{@code run_id} 는 논리적
 * 부모이나 DB FK 가 미설정이다(ERD §4.2 — 보존 14일, 이름·버스번호를 스냅샷으로 담아 자립한다).
 * {@code recipient_role} 은 CHECK 가 부재해 스키마가 값을 보장하지 않는다(Ruling 55) — 잘못된 값은
 * 이 행을 다시 읽는 순간 {@link Role.Db#convertToEntityAttribute} 의 {@link Enum#valueOf} 에서 터진다.
 */
@Entity
@Table(name = "notification_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "recipient_account_id", nullable = false)
    private Long recipientAccountId;

    @Column(name = "recipient_name", length = 50, nullable = false)
    private String recipientName;

    @Convert(converter = Role.Db.class)
    @Column(name = "recipient_role", length = 20, nullable = false)
    private Role recipientRole;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "student_name", length = 50)
    private String studentName;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "bus_no", length = 20)
    private String busNo;

    @Convert(converter = NotificationType.Db.class)
    @Column(name = "type", length = 30, nullable = false)
    private NotificationType type;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "text", nullable = false)
    private String body;

    @Column(name = "popup", nullable = false)
    private boolean popup;

    @Convert(converter = PushState.Db.class)
    @Column(name = "push_state", length = 10, nullable = false)
    private PushState pushState;

    @Column(name = "push_attempts", nullable = false)
    private int pushAttempts;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "fail_reason", length = 200)
    private String failReason;

    @Column(name = "dedup_key", length = 120, nullable = false)
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "acked", nullable = false)
    private boolean acked;

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;

    private NotificationLog(Long academyId, Long recipientAccountId, String recipientName,
            Role recipientRole, NotificationType type, String title, String body,
            String dedupKey, OffsetDateTime createdAt) {
        this.academyId = academyId;
        this.recipientAccountId = recipientAccountId;
        this.recipientName = recipientName;
        this.recipientRole = recipientRole;
        this.type = type;
        this.title = title;
        this.body = body;
        this.popup = false;
        this.pushState = PushState.PENDING;
        this.pushAttempts = 0;
        this.dedupKey = dedupKey;
        this.createdAt = createdAt;
        this.acked = false;
    }

    /**
     * 알림을 유발한 이벤트와 같은 트랜잭션에서 발송 대기 행을 만든다(아웃박스 패턴) — 발송 시도·상태 갱신은
     * 도메인 Phase 담당. 파라미터 9개는 §20.2 기준(4개)을 크게 넘지만, NN 필드 전부가 아웃박스 삽입
     * 시점에 필요해 규약 §3(팩토리 분화 금지)을 우선했다.
     */
    public static NotificationLog forOutbox(Long academyId, Long recipientAccountId,
            String recipientName, Role recipientRole, NotificationType type, String title,
            String body, String dedupKey, OffsetDateTime createdAt) {
        return new NotificationLog(academyId, recipientAccountId, recipientName, recipientRole,
                type, title, body, dedupKey, createdAt);
    }

    /**
     * 읽음 시각을 남긴다(NTF-08, API_SPEC §3.13) — <b>최초 1회만</b> 기록한다. 이미 읽은 알림을 다시
     * 열어도 {@code read_at} 이 뒤로 밀리지 않아야 "언제 처음 봤는가"가 보존된다.
     */
    public void markRead(OffsetDateTime now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    /**
     * 수신 확인(NTF-10)을 남긴다 — <b>중요 통지에 한해</b> 호출자(읽음 처리 서비스)가 부른다. 어떤
     * 종류가 "중요"인지는 이 엔티티가 판단하지 않는다(USER_FLOWS §10.2 규칙4·5 — 지연·미승차·노선
     * 변경 3종). {@link #markRead} 와 마찬가지로 최초 1회만 기록해 {@code acked_at} 이 재확인으로
     * 밀리지 않는다. 관계자 알림 로그(§5.17 미확인 배지, T3 담당)가 이 필드를 그대로 읽는다.
     */
    public void ack(OffsetDateTime now) {
        if (!this.acked) {
            this.acked = true;
            this.ackedAt = now;
        }
    }
}
