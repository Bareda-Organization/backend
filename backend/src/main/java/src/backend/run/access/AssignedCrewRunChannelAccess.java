package src.backend.run.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.global.security.access.RunChannelAccess;

/** 회차 채널 구독 인가 — 그 회차에 배치된 기사 · 동승자만 통과시킨다({@link RunAssignmentAccess#assertAssignedDriverOrEscort}). */
@Component
@RequiredArgsConstructor
class AssignedCrewRunChannelAccess implements RunChannelAccess {

    private final RunAssignmentAccess runAssignmentAccess;

    @Override
    public void assertMayFollowRun(AuthUser subscriber, Long runId) {
        runAssignmentAccess.assertAssignedDriverOrEscort(subscriber, runId);
    }
}
