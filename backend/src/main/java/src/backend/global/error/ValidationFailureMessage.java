package src.backend.global.error;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/**
 * 검증 실패 한 건을 응답 문구 한 줄로 고른다. 위반 목록의 순서는 검증기가 정하지 않으므로 필드명(snake_case) 순, 같은 필드는 문구 순으로 첫 위반을 골라
 * 같은 입력이 어느 요청에서나 같은 문구를 얻게 한다. 문구는 {@code ValidationMessages} 번들이 한국어로 고정한다.
 */
final class ValidationFailureMessage {

    private record Line(String field, String text) {
    }

    private ValidationFailureMessage() {
    }

    /** 첫 위반의 {@code 필드명: 사유} — 위반이 없으면 빈 값. */
    static Optional<String> of(BindingResult result) {
        return result.getFieldErrors().stream()
                .map(ValidationFailureMessage::line)
                .min(Comparator.comparing(Line::field).thenComparing(Line::text))
                .map(Line::text);
    }

    /** 타입 변환 실패는 자바 타입명이 든 기본 문구 대신 고정 문구를 쓴다. */
    private static Line line(FieldError error) {
        String field = snakeCase(error.getField());
        String text = error.isBindingFailure()
                ? field + " 값의 형식이 올바르지 않습니다"
                : field + ": " + error.getDefaultMessage();
        return new Line(field, text);
    }

    /** 자바 필드 경로({@code newStudent.name})를 요청 본문의 키 표기({@code new_student.name}, Ruling 104)로 옮긴다. */
    private static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
