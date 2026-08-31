package src.backend.exception.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 현장 예외 보고 등록 요청(API_SPEC §4.13, EXC-02·03).
 *
 * <p>{@code type} 을 문자열로 받아 커맨드 서비스가 {@code ApiValues.reportType(...)} 으로 변환한다
 * — enum 타입에 직접 바인딩하면 실패가 스프링의 매핑 불가 400 으로 새고, 이 방식은 항상
 * {@code 422 VALIDATION_FAILED} 로 답한다.
 *
 * <p>{@code riderId} 는 {@code type=guardian_absent} 일 때만 필수다(§4.13). Bean Validation
 * 애너테이션 하나로 "다른 필드 값에 따라 필수 여부가 갈리는 조건"을 표현할 수 없어, 그 검증은 커맨드
 * 서비스가 한다.
 */
public record ExceptionReportCreateRequest(
        @NotBlank String type,
        @NotBlank String memo,
        Long riderId) {
}
