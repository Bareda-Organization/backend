package src.backend.global.security;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.access.AcademyScope;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.repository.StudentRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * STOMP CONNECT 인증과 SUBSCRIBE 인가를 한 곳에서 처리한다(ARCHITECTURE §5.1 3층 — 계정 상태 게이트 ·
 * 역할 권한 · 자원 소속 검증). CONNECT 는 세션 수립 시 1회만 인증하고, 이후 같은 세션의 모든 메시지는
 * 그때 심어둔 Principal(AuthUser)을 그대로 쓴다.
 *
 * <p>구독 목적지 4종({@code /topic/students/{studentId}/run} · {@code /topic/manager/runs/{runId}} ·
 * {@code /topic/academy/{academyId}/live} · {@code /topic/admin/live})은 조율자가 고정한 계약이다
 * (Ruling 209). 옛 {@code /topic/tenant/{id}/**} 정규식은 이 4종으로 완전히 대체되어 소멸한다
 * (Ruling 121 해소) — N:M 멤버십 시절의 어휘가 남아 있던 자리였고, 실제 구독 채널을 처음 만드는 이
 * Phase 가 그 계약을 처음 쓰는 시점이라 여기서 정리한다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private static final Pattern STUDENT_RUN_PATTERN = Pattern.compile("^/topic/students/(\\d+)/run$");
    private static final Pattern MANAGER_RUN_PATTERN = Pattern.compile("^/topic/manager/runs/(\\d+)$");
    private static final Pattern ACADEMY_LIVE_PATTERN = Pattern.compile("^/topic/academy/(\\d+)/live$");
    private static final Pattern ADMIN_LIVE_PATTERN = Pattern.compile("^/topic/admin/live$");

    private final JwtTokenProvider tokenProvider;
    private final GuardianChildAccess guardianChildAccess;
    private final RunAssignmentAccess runAssignmentAccess;
    private final StudentRepository studentRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // 주의: StompHeaderAccessor.wrap(message)로 얻은 접근자는 새 복사본이라 mutate 해도
        // 원본 message 에 반영되지 않는다. 인바운드 STOMP 채널의 메시지는 mutable 접근자와 함께
        // 만들어지므로, getAccessor 로 "그 접근자"를 그대로 받아와야 setUser 가 세션에 실제로 남는다.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HEADER);
        String token = (header != null && header.startsWith(PREFIX)) ? header.substring(PREFIX.length()) : null;
        if (token == null) {
            throw new IllegalArgumentException("WebSocket 연결에는 Authorization 헤더가 필요합니다");
        }
        Claims claims;
        try {
            claims = tokenProvider.parse(token);
            if (!tokenProvider.isAccessToken(claims)) {
                throw new IllegalArgumentException("access 토큰이 아닙니다");
            }
        } catch (JwtException e) {
            throw new IllegalArgumentException("유효하지 않은 토큰입니다", e);
        }
        AuthUser user = tokenProvider.resolveAuthUser(claims);
        assertActiveAccount(user);
        accessor.setUser(user);
    }

    /**
     * 계정 상태 게이트(목표 8, Ruling 87 이월) — REST 축의 {@code pending} 허용 목록(API_SPEC §1.4:
     * {@code GET /auth/signup-status} · {@code POST /auth/logout} · {@code GET /me} ·
     * {@code POST|DELETE /me/devices})에 WebSocket 으로 대응되는 항목이 없다. STOMP 연결 자체가
     * "실시간 기능 사용"이라 허용할 대상이 없어 <b>전면 거부</b>로 판단했다(REST 허용 목록을 그대로
     * 복제하지 않은 근거 — 보고서 ①항).
     *
     * <p>CONNECT 에서 막으면 {@code accessor.setUser} 가 호출되지 않아 이후 SUBSCRIBE 도 인가 주체가
     * 없어 {@link ErrorCode#UNAUTHORIZED} 로 함께 거부된다 — 단일 관문으로 목표 8 의 "CONNECT ·
     * SUBSCRIBE 둘 다 거부" 를 만족시킨다(ARCHITECTURE §5.2 가 선호하는 단일 구현 지점과 일치).
     */
    private void assertActiveAccount(AuthUser user) {
        switch (user.status()) {
            case PENDING -> throw new BusinessException(ErrorCode.AUTH_PENDING);
            case REJECTED -> throw new BusinessException(ErrorCode.AUTH_REJECTED);
            case BLOCKED -> throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
            case ACTIVE -> {
            }
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        try {
            dispatchSubscribe(accessor, destination);
        } catch (BusinessException e) {
            markForbidden(accessor);
            throw e;
        }
    }

    private void dispatchSubscribe(StompHeaderAccessor accessor, String destination) {
        if (!(accessor.getUser() instanceof AuthUser subscriber)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Matcher studentMatcher = STUDENT_RUN_PATTERN.matcher(destination);
        if (studentMatcher.matches()) {
            authorizeStudentChannel(subscriber, Long.valueOf(studentMatcher.group(1)));
            return;
        }
        Matcher managerMatcher = MANAGER_RUN_PATTERN.matcher(destination);
        if (managerMatcher.matches()) {
            runAssignmentAccess.assertAssignedDriverOrEscort(subscriber, Long.valueOf(managerMatcher.group(1)));
            return;
        }
        Matcher academyMatcher = ACADEMY_LIVE_PATTERN.matcher(destination);
        if (academyMatcher.matches()) {
            authorizeAcademyChannel(subscriber, Long.valueOf(academyMatcher.group(1)));
            return;
        }
        if (ADMIN_LIVE_PATTERN.matcher(destination).matches()) {
            authorizeAdminChannel(subscriber);
            return;
        }
        // 4종 목적지에 없는 구독 — 기본 차단(목표 7, ARCHITECTURE §5.2 "기본 차단" 원칙)
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    /**
     * 학생 채널 — 학부모(연결된 자녀) 또는 학생 본인만 구독한다. STAFF·DRIVER·ESCORT·SYSTEM_ADMIN 이
     * 시도하면 {@link GuardianChildAccess#requireGuardian} 이 guardian 행을 못 찾아 자연히
     * {@link ErrorCode#FORBIDDEN} 으로 막힌다 — 별도 역할 분기를 두지 않는다(재사용 우선).
     */
    private void authorizeStudentChannel(AuthUser subscriber, Long studentId) {
        if (subscriber.role() == Role.STUDENT) {
            boolean self = studentRepository.findByAccountId(subscriber.accountId())
                    .map(student -> student.getId().equals(studentId))
                    .orElse(false);
            if (!self) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return;
        }
        guardianChildAccess.assertLinkedChild(subscriber, studentId);
    }

    /**
     * 관제 채널(학원) — "해당 학원 관계자"(API_SPEC §7)는 {@link Role#STAFF} 를 가리킨다(그 enum
     * 상수의 javadoc: "학원 관계자(메인 관리자 제외)"). {@link AcademyScope#assertAccessible} 만 쓰면
     * 학원 소속만 보고 PARENT·STUDENT·DRIVER·ESCORT 도 자기 학원 채널을 구독할 수 있게 되어 C-08
     * (§1.12)이 막으려는 것(관계자 아닌 사람에게 타 학생의 승하차 현황·ETA 노출)이 새는 통로가 된다 —
     * 그래서 역할 게이트를 먼저 두고, 그 다음에만 학원 소속 검증을 맡긴다(system_admin 은 역할
     * 게이트에서부터 통과해 {@code AcademyScope} 의 플랫폼 범위 예외로 타 학원도 넘나든다).
     */
    private void authorizeAcademyChannel(AuthUser subscriber, Long academyId) {
        if (subscriber.role() != Role.STAFF && !subscriber.hasPlatformScope()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        AcademyScope.assertAccessible(subscriber, academyId);
    }

    /** 관제 채널(메인 관리자) — "메인 관리자"(API_SPEC §7)는 플랫폼 전 학원 범위 권한 보유자뿐이다. */
    private void authorizeAdminChannel(AuthUser subscriber) {
        if (!subscriber.hasPlatformScope()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * SUBSCRIBE 거부를 세션 속성에 남긴다(목표 7) — {@link WebSocketCloseCodes#FORBIDDEN_SUBSCRIPTION_ATTR}
     * 키로, 전송 계층의 {@code ForbiddenSubscriptionCloseFactory} 가 같은 키를 읽어 종료 코드를
     * 4403 으로 바꾼다(두 클래스가 상수를 공유하는 이유는 {@link WebSocketCloseCodes} 참고).
     */
    private void markForbidden(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(WebSocketCloseCodes.FORBIDDEN_SUBSCRIPTION_ATTR, Boolean.TRUE);
        }
    }
}
