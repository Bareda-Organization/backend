package src.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 끝나지 않은 이동 중 회차 강제 종료 요청(API_SPEC §6.17, Ruling 724) — {@code reason} 이 공백뿐이거나 200자를 넘으면
 * {@code 422 VALIDATION_FAILED} 다(상한은 다른 자유 입력 사유·메모와 같은 200자 — 감사 행 {@code detail.reason} 에 무제한 글이 들어가지 않게 한다, BR-349).
 */
public record ForceFinishRequest(@NotBlank @Size(max = 200) String reason) {
}
