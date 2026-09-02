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

    /**
     * 3종 토글을 한 번에 바꾼다(API_SPEC §3.14 PATCH, Phase 12 목표 7) — 부분 갱신이 아니라 전체
     * 교체다. 대상 밖 필드·누락 필드의 판정(422)은 이 메서드가 아니라 호출부(서비스 계층)가
     * {@code AcademySettingUpdateRequest} 와 같은 근거로 먼저 끝내고, 이 메서드는 이미 유효한
     * 3개 값만 받는다.
     */
    public void changeSettings(boolean arrive, boolean boarding, boolean noShow, OffsetDateTime updatedAt) {
        this.arrive = arrive;
        this.boarding = boarding;
        this.noShow = noShow;
        this.updatedAt = updatedAt;
    }

    /**
     * 이 계정이 그 종류의 알림을 받을지 판정한다(Phase 12 목표 8, API_SPEC §3.14).
     *
     * <p>3종(도착·등하원·미승차) 밖의 나머지는 설정 항목 자체가 없다 — 지연·비상은 사양이 명시적으로
     * "설정 항목 자체가 부재, 항상 발송" 이라 적었고(NTF-07), 나머지(가입 승인 결과 등)는 사양
     * 어디에도 토글이 정의돼 있지 않다. 이 메서드는 그 둘을 구분하지 않는다 — <b>정의된 토글이 없으면
     * 차단할 근거도 없으므로 전부 항상 발송</b>이 같은 원칙(C-17)의 자연스러운 확장이다.
     *
     * <p>{@code boarding} 토글 하나가 승차·하차·운행 시작 3종을 함께 묶는다(API_SPEC §3.14
     * "등하원(승차·하차·운행 시작) 알림") — 사양이 이미 그렇게 하나로 묶어 서술했기 때문이지 이
     * 구현이 임의로 합친 것이 아니다.
     *
     * <p><b>{@code switch} 식을 {@code default} 없이 쓴다</b> — {@code NotificationType} 에 새
     * 값이 늘 때마다 이 메서드가 어느 쪽으로도 자동 분류하지 않고 <b>컴파일 오류로 멈춰</b> 분류를
     * 강제한다. 이전에는 마지막 분기가 {@code return true}(설정 대상 밖)로 떨어지는 if-else 사슬이라,
     * {@code boarding} 소관인 새 종류를 등록에서 빠뜨려도 조용히 "항상 발송"으로 새는 형태였다 —
     * 그 형태의 결함이 실제로 한 번 났다(2026-09-02 Ruling 223, {@code ALIGHTING_CANCELED} 를
     * 처음엔 존재하지 않는 {@code alighting} 토글 소관으로 잘못 짚었던 사례). 지금 이 파일에는
     * {@code BOARDING_CANCELED}·{@code ALIGHTING_CANCELED} 2종이 아직 없다(T4 미병합) — 병합해
     * 컴파일이 깨지면 그 2종을 {@code BOARDING} 계열 {@code case} 에 추가할 것(둘 다 {@code boarding}
     * 토글 소관, Ruling 223).
     */
    public boolean isEnabledFor(NotificationType type) {
        return switch (type) {
            case ARRIVE -> arrive;
            case BOARDING, ALIGHTING, RUN_STARTED -> boarding;
            case NO_SHOW -> noShow;
            case ABSENT, DELAY, RUN_ENDED, SIGNUP_DECIDED, CHANGE_DECIDED, APPROVAL_REQUESTED,
                    INTENT_CHANGED, LINK_REQUESTED, ROUTE_CHANGED, ASSIGNMENT_CHANGED,
                    NO_SHOW_ESCALATED, EMERGENCY, EMERGENCY_CANCELED, EXCEPTION_REPORTED -> true;
        };
    }
}
