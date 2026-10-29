package src.backend.global.security.access;

import src.backend.global.security.AuthUser;

/**
 * STOMP 회차 채널({@code /topic/manager/runs/{runId}}) 구독 인가의 포트 — 구독자가 그 회차에 배치된 기사 · 동승자인지는 회차 모듈이 정하고, 전역 보안은 이 판정만 부른다.
 * 거절은 {@code BusinessException(FORBIDDEN)} 이다.
 */
public interface RunChannelAccess {

    /** 구독자가 그 회차에 배치된 기사 또는 동승자가 아니면 {@code FORBIDDEN} 을 던진다. */
    void assertMayFollowRun(AuthUser subscriber, Long runId);
}
