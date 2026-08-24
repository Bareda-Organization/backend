package src.backend.global.security;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 인증된 사용자(요청 주체). SecurityContext 의 principal 로 들어가며,
 * 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser} 로 주입받는다.
 *
 * <p>계정 1개가 학원 1곳에 속하는 단일 소속 모델로 축소했다 — 옛 {@code User}↔{@code Tenant} N:M
 * 멤버십 목록은 더 이상 없고 {@code academyId} 하나뿐이다. 역할 타입화(Role enum)와
 * 계정 상태({@code PENDING} 등) 게이트는 규칙 `AUTH-02` 재작성 대상(Phase 2)이라 여기 없다.
 *
 * <p>{@link Principal}도 구현해 STOMP 세션(CONNECT 시 1회 인증)의 사용자로도 그대로 쓴다 —
 * REST 요청 인증과 WebSocket 세션 인증이 같은 타입을 공유한다.
 */
public record AuthUser(Long accountId, Long academyId, String role) implements Principal {

    /** {@link Principal#getName()} — STOMP 세션 등 Principal 이 필요한 곳에서 식별자로 쓴다. */
    @Override
    public String getName() {
        return String.valueOf(accountId);
    }

    /** ROLE_* authority 단일 값(멤버십이 여러 개이던 옛 모델과 달리 항상 정확히 하나). */
    public Collection<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
