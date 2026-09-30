package src.backend.run.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 강제 추가 요청(RTE-06, API_SPEC §5.7) — {@code studentId}·{@code newStudent} 는 배타적 조건부
 * 필드라 DTO 애너테이션만으로 표현할 수 없다. 판정은 {@code ForcedAdditionCommandService} 가 한다.
 */
public record ForcedAdditionRequest(Long studentId, @Valid NewStudentRequest newStudent, @NotBlank String address,
        @Size(max = 200) String note) {
}
