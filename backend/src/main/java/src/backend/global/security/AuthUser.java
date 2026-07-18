package src.backend.global.security;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import src.backend.user.entity.Role;

/**
 * 인증된 사용자(요청 주체). SecurityContext 의 principal 로 들어가며,
 * 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser} 로 주입받는다.
 *
 * 권한 모델 이원화:
 *  - 역할(Role) → Spring Security authority(ROLE_*) → {@code @PreAuthorize} 로 검사
 *  - 학원(tenant) 격리 → memberships 를 서비스 계층에서 직접 확인
 *
 * memberships 는 "이 사용자가 어느 학원에서 어떤 역할인지"의 집합이다.
 * 플랫폼 관리자는 tenantId 가 null 인 멤버십(전역 역할)을 가진다.
 *
 * {@link Principal}도 구현해 STOMP 세션(CONNECT 시 1회 인증)의 사용자로도 그대로 쓴다 —
 * REST 요청 인증과 WebSocket 세션 인증이 같은 타입을 공유한다.
 */
public record AuthUser(Long userId, String email, List<Membership> memberships) implements Principal {

    public record Membership(Long tenantId, Role role) {}

    /** {@link Principal#getName()} — STOMP 세션 등 Principal 이 필요한 곳에서 식별자로 쓴다. */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    /** ROLE_* authority 목록(중복 역할 제거). */
    public Collection<GrantedAuthority> authorities() {
        return memberships.stream()
                .map(Membership::role)
                .distinct()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toList());
    }

    /** 이 사용자가 소속된 학원 id 집합(플랫폼 관리자의 null 은 제외). */
    public Set<Long> tenantIds() {
        return memberships.stream()
                .map(Membership::tenantId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    public boolean belongsToTenant(Long tenantId) {
        return tenantId != null && tenantIds().contains(tenantId);
    }

    public boolean hasRole(Role role) {
        return memberships.stream().anyMatch(m -> m.role() == role);
    }

    public boolean isPlatformAdmin() {
        return hasRole(Role.PLATFORM_ADMIN);
    }

    /** 대표 학원 id(멤버십 중 첫 번째). 학원 관리자 조회의 기본 테넌트로 쓴다. */
    public Long primaryTenantId() {
        return tenantIds().stream().findFirst().orElse(null);
    }

    // ── JWT 클레임 직렬화: Membership ↔ "tenantId:ROLE" 문자열 ──
    // 토큰에 멤버십을 담아 필터가 DB 조회 없이 principal 을 복원하게 한다.

    public static String encode(Membership m) {
        String tenant = m.tenantId() == null ? "" : String.valueOf(m.tenantId());
        return tenant + ":" + m.role().name();
    }

    public static Membership decode(String encoded) {
        int sep = encoded.indexOf(':');
        String tenant = encoded.substring(0, sep);
        Role role = Role.valueOf(encoded.substring(sep + 1));
        return new Membership(tenant.isEmpty() ? null : Long.valueOf(tenant), role);
    }

    public static AuthUser fromEncoded(Long userId, String email, List<String> encoded) {
        List<Membership> memberships = new ArrayList<>();
        if (encoded != null) {
            for (String e : encoded) {
                memberships.add(decode(e));
            }
        }
        return new AuthUser(userId, email, memberships);
    }
}
