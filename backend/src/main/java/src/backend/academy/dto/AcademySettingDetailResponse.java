package src.backend.academy.dto;

import java.util.Locale;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademySetting;
import src.backend.global.policy.PolicyConstants;
import src.backend.global.retention.RetentionPolicy;

/**
 * 학원 설정 {@code GET} 응답(API_SPEC §5.21, Ruling 820) — 수정 대상인 {@code no_show_wait_minutes} 에 <b>읽기 전용</b>
 * {@code academy} · {@code policy} 를 더한 형태다. {@code PATCH} 응답({@link AcademySettingResponse})은 그대로이고 이 둘을 싣지 않는다.
 *
 * <p>{@code policy} 는 화면에 숫자를 박지 않고 서버가 실제로 쓰는 상수를 그대로 내보낸다 — 상수가 바뀔 때 화면이 갈리지 않게 하려는
 * 값이다({@link Policy#current()}).
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record AcademySettingDetailResponse(int noShowWaitMinutes, AcademyInfo academy, Policy policy) {

    /** 설정 · 학원 · 현재 정책 상수를 §5.21 {@code GET} 응답 모양으로 조립한다. */
    public static AcademySettingDetailResponse of(AcademySetting setting, Academy academy) {
        return new AcademySettingDetailResponse(setting.getNoShowWaitMinutes(), AcademyInfo.from(academy),
                Policy.current());
    }

    /**
     * 요청자 학원의 표시값.
     *
     * @param status {@code active} · {@code inactive}(소문자 — 다른 학원 응답과 같은 형태)
     */
    public record AcademyInfo(String name, String code, String region, String status) {

        /** 학원 엔티티에서 표시값을 옮긴다. */
        public static AcademyInfo from(Academy academy) {
            return new AcademyInfo(academy.getName(), academy.getCode(), academy.getRegion(),
                    academy.getStatus().name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 학원이 바꿀 수 없는 전역 정책 상수(FEATURE_SPEC §2.1).
     *
     * @param confirmLeadMinutes        노선 확정 시점 — 출발 이 분 전
     * @param startWindowMinutes        운행 시작 버튼 활성 창 — 출발 시각 앞뒤 이 분
     * @param changeQuotaPerRun         ②구간 변경 한도 — 회차당 이 횟수
     * @param delayUnitMinutes          지연 알림 단위(분)
     * @param proximityAlertMeters      근접 알림 기준(m)
     * @param notificationRetentionDays 알림 보관(일)
     */
    public record Policy(int confirmLeadMinutes, int startWindowMinutes, int changeQuotaPerRun,
            int delayUnitMinutes, int proximityAlertMeters, int notificationRetentionDays) {

        /** 서버가 지금 쓰는 상수에서 읽는다 — 값을 이 클래스에 다시 적지 않는다. */
        public static Policy current() {
            return new Policy((int) PolicyConstants.CONFIRM_LEAD.toMinutes(),
                    (int) PolicyConstants.START_WINDOW.toMinutes(), PolicyConstants.CHANGE_QUOTA_PER_RUN,
                    PolicyConstants.DELAY_UNIT_MINUTES, PolicyConstants.PROXIMITY_ALERT_METERS,
                    (int) RetentionPolicy.NOTIFICATION_LOG_RETENTION.toDays());
        }
    }
}
