package src.backend.notification.dto;

import src.backend.notification.entity.NotificationSetting;

/** 알림 설정 조회·수정 응답(API_SPEC §3.14) — 문서화된 필드는 `arrive`·`boarding`·`no_show` 3개다. */
public record NotificationSettingResponse(boolean arrive, boolean boarding, boolean noShow) {

    public static NotificationSettingResponse from(NotificationSetting setting) {
        return new NotificationSettingResponse(setting.isArrive(), setting.isBoarding(), setting.isNoShow());
    }
}
