package src.backend.account.dto;

/** 로그아웃 요청(API_SPEC §2.7) — §2.6 과 같이 쿠키가 우선이고, 없을 때만 이 필드를 읽는다. */
public record LogoutRequestPayload(String refreshToken) {
}
