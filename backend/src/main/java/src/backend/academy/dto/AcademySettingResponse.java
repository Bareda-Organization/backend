package src.backend.academy.dto;

import src.backend.academy.entity.AcademySetting;

/** 학원별 설정 조회·수정 응답(API_SPEC §5.21) — 문서화된 필드는 {@code no_show_wait_minutes} 하나다. */
public record AcademySettingResponse(int noShowWaitMinutes) {

    public static AcademySettingResponse from(AcademySetting setting) {
        return new AcademySettingResponse(setting.getNoShowWaitMinutes());
    }
}
