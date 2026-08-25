package src.backend.global.security;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 인증된 사용자(요청 주체). SecurityContext 의 principal 로 들어가며,
 * 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser} 로 주입받는다.
 *
 * <p>계정 1개가 학원 1곳에 속하는 단일 소속 모델이다 — 옛 {@code User}↔{@code Tenant} N:M
 * 멤버십 목록은 없고 {@code academyId} 하나뿐이다. {@code role} 은 문자열이 아니라 {@link Role}
 * 이라 오탈자·대소문자 혼용이 컴파일 에러가 되고, {@code RolePermissions} 부여표·
 * {@code hasAuthority(...)} 애너테이션과 같은 상수만 참조한다.
 *
 * <p>{@code academyId} 가 null 인 것은 {@link Role#SYSTEM_ADMIN} 뿐이라는 사실을 컴팩트 생성자로
 * 강제한다 — {@code ck_account_academy_scope} DB 제약(V1__init_schema.sql)과 동일한 조건이라,
 * 이 제약을 위반하는 행이 있다면 DB 에 들어가기 전에 여기서 먼저 걸린다. system_admin 이
 * academyId 를 가지는 것까지 막지는 않는다 — DB 제약 자체가 그것까지는 금지하지 않는다.
 *
 * <p>{@code status} 는 발급 시점의 계정 상태를 토큰에서 그대로 옮긴 값이다 — {@code global.security.gate}
 * 의 계정 상태 게이트가 이 값만 보고 pending·rejected 계정의 API 접근을 허용 목록으로 제한한다
 * (API_SPEC §1.4). 상태가 바뀐 뒤 반영되려면 재로그인(재발급)이 필요하다. {@code role} 과 마찬가지로
 * 문자열이 아니라 {@link AccountStatus} 다 — 대소문자를 손으로 맞출 필요가 없어, 발급 쪽이
 * {@code status.name()}(대문자)을 쓰고 게이트가 소문자 리터럴을 기대하는 식의 불일치가 컴파일
 * 시점에 사라진다(Phase 2 Task 2 리뷰 라운드 1 Important #2).
 *
 * <p>{@link Principal}도 구현해 STOMP 세션(CONNECT 시 1회 인증)의 사용자로도 그대로 쓴다 —
 * REST 요청 인증과 WebSocket 세션 인증이 같은 타입을 공유한다.
 */
public record AuthUser(Long accountId, Long academyId, Role role, AccountStatus status) implements Principal {

    /**
     * {@code system_admin} 이 아닌 역할은 {@code academyId} 가 null 일 수 없다(ck_account_academy_scope).
     * {@code status} 는 어떤 역할이든 null 일 수 없다 — null 을 허용하면 계정 상태 게이트가 "인증은
     * 됐는데 상태를 모르는 요청" 을 미인증과 구별하지 못해 전 API 를 열어 버린다(리뷰 라운드 1
     * Critical #1). {@code status} 를 발급하지 않는 토큰은 여기서 즉시 실패해야, 그 실패가 게이트를
     * 우회하는 통로가 되지 않는다.
     */
    public AuthUser {
        if (role != Role.SYSTEM_ADMIN && academyId == null) {
            throw new IllegalStateException(
                    "system_admin 이 아닌 역할은 academyId 가 null 일 수 없다(ck_account_academy_scope): role=" + role);
        }
        if (status == null) {
            throw new IllegalStateException(
                    "AuthUser 는 계정 상태 없이 생성할 수 없다 — 상태 미상 요청에 게이트를 여는 사고를 막는다: accountId=" + accountId);
        }
    }

    /** {@link Principal#getName()} — STOMP 세션 등 Principal 이 필요한 곳에서 식별자로 쓴다. */
    @Override
    public String getName() {
        return String.valueOf(accountId);
    }

    /** ROLE_* authority 단일 값(멤버십이 여러 개이던 옛 모델과 달리 항상 정확히 하나). */
    public Collection<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** 플랫폼 전역 범위(학원 격리 예외) 여부 — {@code academyId == null} 을 여기저기서 직접 비교하지 않게 한다. */
    public boolean hasPlatformScope() {
        return role == Role.SYSTEM_ADMIN;
    }
}
