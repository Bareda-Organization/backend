package src.backend.global.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 모든 요청에서 한 번 실행되며, Authorization: Bearer 토큰을 검증해
 * SecurityContext 에 인증 정보를 채운다.
 * 토큰이 없거나 유효하지 않으면 인증 없이 통과시킨다(보호 자원이면 이후 인가 단계에서 차단).
 *
 * <p>{@code @Component} 이면서 {@code Filter} 라 서블릿 컨테이너 자동 등록 대상도 된다 — 시큐리티
 * 체인 밖에서 중복 실행되지 않도록 {@link SecurityConfig#jwtAuthenticationFilterRegistration}가
 * 그 자동 등록을 꺼 둔다(그 메서드 Javadoc에 이중 실행이 왜 인증을 지우는지 적어 뒀다).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    /** 토큰 거부 사유({@link ErrorCode})를 엔트리 포인트로 넘기는 요청 속성 이름. */
    static final String AUTH_FAILURE_ATTR = JwtAuthenticationFilter.class.getName() + ".failure";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthUser principal = tokenProvider.authenticateAccess(token);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BusinessException e) {
                // 위조·만료 토큰은 인증을 세우지 않는다(익명으로 진행) — 보호 자원이면 엔트리 포인트가
                // 여기 남긴 코드(TOKEN_EXPIRED · UNAUTHORIZED)로 401 본문을 쓴다(BR-033)
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_FAILURE_ATTR, e.getErrorCode());
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
