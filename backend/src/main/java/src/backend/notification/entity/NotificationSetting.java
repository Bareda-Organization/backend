package src.backend.notification.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 on/off 설정 — 대상이 도착·승차·미승차 3종으로 고정이고 학부모·학생 계정에만 행이 생겨
 * {@code account} 와 별도 테이블이다(ERD §3.6).
 *
 * <p>{@code account.id} 를 그대로 PK 로 쓰는 1:1 확장 테이블이라 {@code @GeneratedValue} 를 두지
 * 않는다 — 값은 계정 생성 시점에 호출자가 직접 넣는다(엔티티 작성 규약 §4, {@link src.backend.academy.entity.AcademySetting} 과 동일 패턴).
 * {@code updated_at} 만 있고 {@code created_at} 이 없어 {@code BaseTimeEntity} 를 상속하지 않는다
 * (엔티티 작성 규약 §4.4, Ruling 62) — 평범한 필드로 두고 팩토리 파라미터로 받는다.
 */
@Entity
@Table(name = "notification_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "arrive", nullable = false)
    private boolean arrive;

    @Column(name = "boarding", nullable = false)
    private boolean boarding;

    @Column(name = "no_show", nullable = false)
    private boolean noShow;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private NotificationSetting(Long accountId, OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.arrive = true;
        this.boarding = true;
        this.noShow = true;
        this.updatedAt = updatedAt;
    }

    /** 학부모·학생 계정이 만들어질 때(연결 승인 등) 기본값(전부 ON)으로 생성한다. */
    public static NotificationSetting forAccount(Long accountId, OffsetDateTime updatedAt) {
        return new NotificationSetting(accountId, updatedAt);
    }
}
