package src.backend.run.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 강제 추가 요청(RTE-06, API_SPEC §5.7) — {@code studentId}·{@code newStudent} 는 배타적 조건부
 * 필드라 DTO 애너테이션만으로 표현할 수 없다. 판정은 {@code ForcedAdditionCommandService} 가 한다.
 */
public record ForcedAdditionRequest(Long studentId, NewStudentRequest newStudent, @NotBlank String address,
        String note) {
}
