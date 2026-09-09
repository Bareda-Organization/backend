package src.backend.global.security.gate;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 계정 상태 게이트(C-01 · API_SPEC §1.4) — pending·rejected 계정이 허용 목록 밖 핸들러를
 * 호출하면 컨트롤러에 닿기 전에 차단한다. 판정 지점을 이 한 곳으로 모아, 새 엔드포인트가
 * 애너테이션 없이 추가돼도 기본값이 차단이 되게 한다(허용 목록 방식).
 *
 * <p>{@code active} 는 그대로 통과시켜 역할 권한(②층)·자원 격리(③층) 판정으로 넘긴다.
 * {@code blocked} 는 로그인 단계에서 이미 걸러지는 상태라({@code Account.assertNotBlocked}) 이
 * 게이트까지 도달하면 이례적인 상황이나, 방어적으로 여기서도 동일하게 차단한다 — {@code switch} 의
 * {@code default} 분기가 {@code BLOCKED} 뿐 아니라 향후 {@link AccountStatus} 에 값이 추가돼도
 * 자동으로 차단측에 붙게 한다(신규 상태를 깜빡 허용측에 추가하지 않는 이상 안전).
 *
 * <p>{@link AuthUser#status()} 가 {@link AccountStatus} 로 타입화돼 있어(리뷰 라운드 1 Important #2)
 * "상태를 알 수 없는 인증된 요청"이라는 제3의 경우가 애초에 존재하지 않는다 — {@code AuthUser} 는
 * status 없이 생성될 수 없으므로, 이 메서드는 미인증(authUser == null)과 4개 상태값만 고려하면 된다.
 */
@Component
public class AccountStatusGateInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // 정적 리소스 등 컨트롤러가 아닌 핸들러는 게이트 대상이 아니다.
        }
        AuthUser authUser = resolveAuthUser();
        if (authUser == null || authUser.status() == AccountStatus.ACTIVE) {
            return true; // 미인증 요청은 인증 계층이 처리하고, active 는 이 게이트를 통과한다.
        }

        boolean allowedWhenPending = handlerMethod.hasMethodAnnotation(AllowedWhenPending.class);
        switch (authUser.status()) {
            case PENDING -> {
                if (!allowedWhenPending) {
                    throw new BusinessException(ErrorCode.AUTH_PENDING);
                }
                return true;
            }
            case REJECTED -> {
                boolean allowedWhenRejected = handlerMethod.hasMethodAnnotation(AllowedWhenRejected.class);
                if (!allowedWhenPending && !allowedWhenRejected) {
                    throw new BusinessException(ErrorCode.AUTH_REJECTED);
                }
                return true;
            }
            default -> throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
        }
    }

    private AuthUser resolveAuthUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser authUser) {
            return authUser;
        }
        return null;
    }
}
