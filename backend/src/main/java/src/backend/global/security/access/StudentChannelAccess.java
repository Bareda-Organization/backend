package src.backend.global.security.access;

import src.backend.global.security.AuthUser;

/**
 * STOMP 학생 채널({@code /topic/students/{studentId}/run}) 구독 인가의 포트 — 학생 본인 또는 연결된 보호자인지는 학생 모듈이 정하고, 전역 보안은 이 판정만 부른다.
 * 거절은 {@code BusinessException(FORBIDDEN)} 이다.
 */
public interface StudentChannelAccess {

    /** 구독자가 그 학생 본인(퇴원하지 않은)도 연결된 보호자도 아니면 {@code FORBIDDEN} 을 던진다. */
    void assertMayFollowStudent(AuthUser subscriber, Long studentId);
}
