package src.backend.account.command;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 로그인 성공 결과(내부 전달용) — 원문 토큰과 계정 정보만 담는다. 이 값을 앱·웹 어느 형태로
 * 응답할지는 전혀 모른다 — 그 판단은 컨트롤러가 한다(브리프 §3, "서비스는 클라이언트 종류를
 * 모른다").
 */
public record LoginResult(
        String accessToken,
        String refreshToken,
        long refreshTokenValiditySeconds,
        Long accountId,
        Long academyId,
        Role role,
        AccountStatus status,
        String academyName) {
}
