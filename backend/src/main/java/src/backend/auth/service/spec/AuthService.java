package src.backend.auth.service.spec;

import src.backend.auth.dto.LoginRequest;
import src.backend.auth.dto.SignupRequest;
import src.backend.auth.dto.TokenResponse;

/**
 * 인증 비즈니스 로직의 계약(인터페이스) — 회원가입/로그인/토큰 재발급.
 *
 * <p>컨트롤러·테스트는 이 인터페이스에만 의존하고, 실제 구현은 {@link AuthServiceImpl} 이 담당한다.
 * 추후 인증 방식이 바뀌어도(예: OAuth2 연동, 외부 IdP) 구현체만 교체하면 되도록 계약을 분리한다.
 */
public interface AuthService {

    /** 회원가입 — 이메일 중복 검사 후 BCrypt 저장, 역할·학원 멤버십을 부여한다. */
    void signup(SignupRequest req);

    /** 로그인 — 자격 검증 후 학원·역할 멤버십을 담은 access/refresh 토큰을 발급한다. */
    TokenResponse login(LoginRequest req);

    /** 토큰 재발급 — 유효한 refresh 토큰으로 새 토큰 쌍을 발급한다. */
    TokenResponse refresh(String refreshToken);
}
