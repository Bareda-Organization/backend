package src.backend.exception.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 비상 신고 요청(EXC-04, Phase 11 T2 목표 5·8). {@code type} 을 문자열로 받는 이유는
 * {@code RiderStatusUpdateRequest} 와 같다 — 잘못된 값을 자동 바인딩(400)이 아니라 서비스 계층이
 * {@code 422 VALIDATION_FAILED} 로 판정해야 한다.
 *
 * <p><b>{@code lat}·{@code lng} 를 받지 않는다</b> — 목표 8 의 위치는 요청 본문이 아니라 서버가
 * {@code RunPositionCache}(Redis, T1 소유 계약)에서 자동으로 읽어 붙인다. 클라이언트가 좌표를
 * 함께 보내게 하면 그 좌표가 실제 최신 위치와 다를 때 어느 쪽을 믿을지가 또 다른 판정거리가 된다.
 *
 * <p>{@code occurredAt} 이 비어 있으면 서버가 접수 시각({@code receivedAt} 과 동일)으로 채운다
 * ({@code EmergencyCommandService} 참고) — 클라이언트 기기 시계를 신뢰하지 않는 판단이다.
 */
public record EmergencyRaiseRequest(@NotBlank String type, String memo, @NotNull UUID clientKey,
        OffsetDateTime occurredAt) {
}
