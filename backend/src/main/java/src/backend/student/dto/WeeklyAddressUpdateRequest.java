package src.backend.student.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 요일별 주소 저장 요청(P-05 · STU-05, API_SPEC §3.7 {@code PATCH}).
 *
 * <p>보낸 칸만 반영된다 — 요일 14칸을 전부 실을 필요가 없고, 싣지 않은 칸은 그대로 남는다. 전체
 * 교체가 아니라 부분 갱신인 것이 {@code PATCH} 인 이유다.
 *
 * <p>상한 14 = 요일 7 × 방향 2 — 칸마다 외부 지오코딩을 부르므로 상한이 없으면 요청 하나가 호출 수를
 * 정한다(BR-059).
 */
public record WeeklyAddressUpdateRequest(
        @NotEmpty @Size(max = 14) @Valid List<WeeklyAddressEntryRequest> entries) {
}
