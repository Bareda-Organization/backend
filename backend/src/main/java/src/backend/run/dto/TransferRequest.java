package src.backend.run.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 버스 간 이동 요청(RTE-07, API_SPEC §5.8) — {@code stopId}·{@code address} 는 배타적이며
 * 하나가 필수인 조건부 필드라 DTO 애너테이션만으로 표현할 수 없다. 판정은
 * {@code TransferCommandService} 가 한다({@code ForcedAdditionRequest} 와 같은 형태).
 */
public record TransferRequest(
        @NotNull Long fromRunId,
        @NotNull Long toRunId,
        Long stopId,
        String address,
        @Size(max = 200) String note) {
}
