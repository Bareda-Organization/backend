package src.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 강제 확정 콘솔 개입 요청(API_SPEC §6.14, F3 S2 목표 11) — {@code reason} 은 공백만이거나 200자를 넘으면
 * {@code 422 VALIDATION_FAILED} 다. {@code DelayRequest#reason} 과 같은 이유로 빈 검증 애너테이션이
 * 이 판정을 그대로 맡는다({@code @NotBlank} 는 공백뿐인 값도 거른다). 200자는 다른 자유 입력 사유·메모와 같은 상한이다(BR-349).
 */
public record ForceConfirmRequest(@NotBlank @Size(max = 200) String reason) {
}
