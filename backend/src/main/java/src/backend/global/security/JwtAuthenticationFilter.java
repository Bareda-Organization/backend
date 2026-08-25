package src.backend.global.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에서 한 번 실행되며, Authorization: Bearer 토큰을 검증해
 * SecurityContext 에 인증 정보를 채운다.
 * 토큰이 없거나 유효하지 않으면 인증 없이 통과시킨다(보호 자원이면 이후 인가 단계에서 차단).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        System.err.println("[DEBUG] thread=" + Thread.currentThread()
                + " strategyClass=" + SecurityContextHolder.getContextHolderStrategy().getClass()
                + " strategy=" + System.identityHashCode(SecurityContextHolder.getContextHolderStrategy())
                + " ctxBefore=" + System.identityHashCode(SecurityContextHolder.getContext())
                + " ctxClass=" + SecurityContextHolder.getContext().getClass());
        System.err.println("[DEBUG] token=" + token);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = tokenProvider.parse(token);
                System.err.println("[DEBUG] claims=" + claims);
                if (tokenProvider.isAccessToken(claims)) {
                    AuthUser principal = tokenProvider.resolveAuthUser(claims);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContext ctx = SecurityContextHolder.getContext();
                    ctx.setAuthentication(authentication);
                    System.err.println("[DEBUG] ctxUsedToSet=" + System.identityHashCode(ctx)
                            + " set auth=" + SecurityContextHolder.getContext().getAuthentication()
                            + " ctxRightAfterSet=" + System.identityHashCode(SecurityContextHolder.getContext()));
                } else {
                    System.err.println("[DEBUG] not access token, type claim=" + claims.get("type"));
                }
            } catch (JwtException | IllegalArgumentException e) {
                System.err.println("[DEBUG] jwt exception: " + e);
                // 위조·만료 토큰은 인증을 세우지 않는다(익명으로 진행 → 보호 자원이면 401/403)
                SecurityContextHolder.clearContext();
            }
        }
        System.err.println("[DEBUG] req=" + System.identityHashCode(request) + " " + request.getClass());
        chain.doFilter(request, response);
        System.err.println("[DEBUG] after chain: strategyClass=" + SecurityContextHolder.getContextHolderStrategy().getClass()
                + " strategy=" + System.identityHashCode(SecurityContextHolder.getContextHolderStrategy())
                + " ctxAfter=" + System.identityHashCode(SecurityContextHolder.getContext())
                + " authAfter=" + SecurityContextHolder.getContext().getAuthentication());
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
