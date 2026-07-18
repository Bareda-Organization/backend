package src.backend.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;

/**
 * 알림 발송 로그 — "이벤트 1건 = 발송 1건"을 보장하는 멱등 기록.
 * dedupKey(유형+학생+대상일자+정류장/단계)에 유니크 제약을 걸어, 같은 이벤트가
 * 여러 번 감지돼도(GPS 재관측, 재시도 등) DB 레벨에서 중복 저장을 최종 방어한다.
 */
@Entity
@Table(name = "notification_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false, unique = true)
    private String dedupKey;

    @Column(nullable = false)
    private String message;

    @Builder
    public NotificationLog(Long tenantId, Long studentId, NotificationType type, String dedupKey, String message) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.type = type;
        this.dedupKey = dedupKey;
        this.message = message;
    }
}
