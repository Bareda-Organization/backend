package src.backend.global.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 열린 STOMP 세션의 계정 상태를 30초마다 묶어 점검한다(R57 RT-7 · Ruling 899 M) — 권한을 잃은 계정의 세션은 다음 방송이 {@code TOKEN_EXPIRED}
 * ERROR 로 바뀌며 닫힌다. 점검 간격이 길수록 퇴사한 계정이 방송을 받는 시간이 길어지고(Ruling 880), 짧을수록 DB 를 자주 두드린다.
 *
 * <p>{@code @SchedulerLock} 을 달지 않는다 — 세션은 인스턴스마다 따로 있어 락을 놓친 인스턴스의 세션이 점검되지 않는다. 인스턴스 2대가 각자 자기 세션을
 * 점검하는 것이 맞다({@code SchedulerLockConventionTest} 예외 목록에 같은 이유를 적었다).
 */
@Component
@RequiredArgsConstructor
public class StompStandingSweepScheduler {

    private final StompSessionExpiry sessionExpiry;

    /** 열린 세션의 계정 상태를 점검한다. */
    @Scheduled(fixedDelayString = "${app.ws.standing-sweep.interval-ms:30000}",
            initialDelayString = "${app.ws.standing-sweep.initial-delay-ms:30000}")
    public void sweep() {
        sessionExpiry.expireLostAccountSessions();
    }
}
