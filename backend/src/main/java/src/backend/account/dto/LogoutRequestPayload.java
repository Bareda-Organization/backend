package src.backend.account.dto;

import jakarta.validation.constraints.Size;

/**
 * 로그아웃 요청(API_SPEC §2.7) — {@code refreshToken} 은 §2.6 과 같이 쿠키가 우선이고, 없을 때만 이 필드를 읽는다.
 * {@code deviceId} 는 선택 — 있으면 그 기기의 푸시 단말 토큰을 함께 해지한다(§2.11 · Ruling 331).
 */
public record LogoutRequestPayload(String refreshToken, @Size(max = 100) String deviceId) {
}
