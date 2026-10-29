package src.backend.global.security;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.access.AcademyScope;
import src.backend.global.security.access.RunChannelAccess;
import src.backend.global.security.access.StudentChannelAccess;

/**
 * STOMP SUBSCRIBE 인가(ARCHITECTURE §5.1 3층 중 역할 권한 · 자원 소속 검증). 구독 목적지 4종({@code /topic/students/{studentId}/run} ·
 * {@code /topic/manager/runs/{runId}} · {@code /topic/academy/{academyId}/live} · {@code /topic/admin/live})은 고정된 계약이고
 * (Ruling 209) 그 밖의 목적지는 기본 차단이다. {@code /topic/tenant/{id}/**} 목적지는 쓰지 않는다(Ruling 121 해소).
 */
@Component
@RequiredArgsConstructor
public class StompSubscribeAuthorizer {

    private static final Pattern STUDENT_RUN_PATTERN = Pattern.compile("^/topic/students/(\\d{1,18})/run$");
    private static final Pattern MANAGER_RUN_PATTERN = Pattern.compile("^/topic/manager/runs/(\\d{1,18})$");
    private static final Pattern ACADEMY_LIVE_PATTERN = Pattern.compile("^/topic/academy/(\\d{1,18})/live$");
    private static final Pattern ADMIN_LIVE_PATTERN = Pattern.compile("^/topic/admin/live$");

    private final StudentChannelAccess studentChannelAccess;
    private final RunChannelAccess runChannelAccess;

    /** 구독 목적지를 인가하고, 거절되면 4403 으로 닫을 표식을 세션에 남긴다. */
    public void authorize(StompHeaderAccessor accessor) {
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

    /**
     * 목적지 4종을 순서대로 매칭해 채널별 인가로 넘긴다 — 어느 패턴에도 안 걸리면 기본 차단. 숫자 id 는 18자리까지만 패턴에 걸려
     * {@code long} 범위를 넘는 id 도 예외 없이 기본 차단으로 끝난다. 패턴 4개 각각의 인가 메서드 시그니처가 달라(구독자만 · 구독자+id)
     * 표·루프로 데이터화하면 오히려 캐스팅이 늘어, 채널마다 {@code try…} 메서드를 순서대로 부른다.
     */
    private void dispatchSubscribe(StompHeaderAccessor accessor, String destination) {
        if (!(accessor.getUser() instanceof AuthUser subscriber)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        boolean handled = tryStudentChannel(subscriber, destination) || tryManagerChannel(subscriber, destination)
                || tryAcademyChannel(subscriber, destination) || tryAdminChannel(subscriber, destination);
        if (!handled) {
            // 4종 목적지에 없는 구독 — 기본 차단(ARCHITECTURE §5.2 "기본 차단" 원칙)
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 학생 채널 목적지면 인가하고 {@code true}, 아니면 {@code false}. */
    private boolean tryStudentChannel(AuthUser subscriber, String destination) {
        Long studentId = channelId(STUDENT_RUN_PATTERN, destination);
        if (studentId == null) {
            return false;
        }
        authorizeStudentChannel(subscriber, studentId);
        return true;
    }

    /** 매니저 회차 채널 목적지면 인가하고 {@code true}, 아니면 {@code false}. */
    private boolean tryManagerChannel(AuthUser subscriber, String destination) {
        Long runId = channelId(MANAGER_RUN_PATTERN, destination);
        if (runId == null) {
            return false;
        }
        runChannelAccess.assertMayFollowRun(subscriber, runId);
        return true;
    }

    /** 학원 관제 채널 목적지면 인가하고 {@code true}, 아니면 {@code false}. */
    private boolean tryAcademyChannel(AuthUser subscriber, String destination) {
        Long academyId = channelId(ACADEMY_LIVE_PATTERN, destination);
        if (academyId == null) {
            return false;
        }
        authorizeAcademyChannel(subscriber, academyId);
        return true;
    }

    /** 메인 관리자 관제 채널 목적지면 인가하고 {@code true}, 아니면 {@code false}. */
    private boolean tryAdminChannel(AuthUser subscriber, String destination) {
        if (!ADMIN_LIVE_PATTERN.matcher(destination).matches()) {
            return false;
        }
        authorizeAdminChannel(subscriber);
        return true;
    }

    /** 목적지가 패턴과 맞으면 그 안의 숫자 id, 아니면 {@code null}. */
    private static Long channelId(Pattern pattern, String destination) {
        Matcher matcher = pattern.matcher(destination);
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }

    /** 학생 채널 — 학생 본인 또는 연결된 보호자만 구독한다. 판정은 학생 모듈의 {@link StudentChannelAccess} 구현이 한다. */
    private void authorizeStudentChannel(AuthUser subscriber, Long studentId) {
        studentChannelAccess.assertMayFollowStudent(subscriber, studentId);
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
     * SUBSCRIBE 거부를 세션 속성에 남긴다 — {@link WebSocketCloseCodes#FORBIDDEN_SUBSCRIPTION_ATTR}
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
