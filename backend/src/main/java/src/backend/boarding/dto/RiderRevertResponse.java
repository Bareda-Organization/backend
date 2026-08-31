package src.backend.boarding.dto;

import java.time.OffsetDateTime;

/** 상태 정정 응답(API_SPEC §4.7) — {@code status} 는 되돌린 결과값(직전 상태), {@code reverted_at} 은 처리 시각. */
public record RiderRevertResponse(String status, OffsetDateTime revertedAt) {
}
