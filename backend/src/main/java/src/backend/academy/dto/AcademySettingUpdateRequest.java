package src.backend.academy.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 학원별 설정 수정 요청(API_SPEC §5.21 PATCH) — 허용 범위 밖 값의 판정은 자동 바인딩이 아니라
 * {@link src.backend.academy.entity.AcademySetting#changeNoShowWaitMinutes} 가 {@code 422
 * VALIDATION_FAILED} 로 한다({@code RiderStatusUpdateRequest} 와 같은 근거 — 도메인 판정을 서비스
 * 계층에 모은다). {@code @NotNull} 만 여기 둔다 — 필드 자체가 안 왔는지와, 왔지만 범위 밖인지는
 * 다른 실패라 다른 계층이 갈라 맡는다.
 */
public record AcademySettingUpdateRequest(@NotNull Integer noShowWaitMinutes) {
}
