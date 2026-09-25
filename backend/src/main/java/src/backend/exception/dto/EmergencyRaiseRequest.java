package src.backend.exception.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 비상 신고 요청(EXC-04, Phase 11 T2 목표 5·8). {@code type} 을 문자열로 받는 이유는
 * {@code RiderStatusUpdateRequest} 와 같다 — 잘못된 값을 자동 바인딩(400)이 아니라 서비스 계층이
 * {@code 422 VALIDATION_FAILED} 로 판정해야 한다.
 *
 * <p>{@code lat}·{@code lng} 는 선택이다(API_SPEC §4.14, BR-109) — 둘 다 오면 발신 시점 위치로 그대로
 * 저장하고, 없으면 서버가 {@code RunPositionCache}(Redis, T1 소유 계약)의 최신 좌표로 대체한다. 통신
 * 두절 중 발신한 건이 복구 뒤 도착하면 캐시는 이미 다른 지점이라 단말 좌표가 우선이다.
 *
 * <p>{@code occurredAt} 이 비어 있으면 서버가 접수 시각({@code receivedAt} 과 동일)으로 채운다
 * ({@code EmergencyCommandService} 참고) — 클라이언트 기기 시계를 신뢰하지 않는 판단이다.
 */
public record EmergencyRaiseRequest(@NotBlank String type, String memo, @NotNull UUID clientKey,
        OffsetDateTime occurredAt, @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng) {
}
