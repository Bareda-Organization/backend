package src.backend.run.domain;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 이동 중({@code moving}) 회차를 "지금 처리할 대상" 으로 보는 운행일 범위(R46-KFIXBE K-1, Ruling 701) — 오늘과 어제다.
 *
 * <p>어제까지 넣는 이유는 자정을 넘기는 운행 때문이다 — 23시대에 출발한 회차는 운행일이 어제여도 새벽까지 달린다.
 * 그 앞 날짜에도 {@code moving} 으로 남은 회차는 잔류 인원을 정리하지 못한 채 끝나지 않은 것이라, 근접 판정·위치 유실 지표·
 * 노선 잠금이 계속 집으면 처리 집합이 날마다 커지고 경보가 꺼지지 않는다. 그 회차는 이 범위에서 빼고 {@code StaleMovingRun}
 * 경보로 사람에게 드러낸다.
 */
@Component
@RequiredArgsConstructor
public class MovingRunWindowPolicy {

    private static final int GRACE_DAYS = 1;

    private final Clock clock;

    /** 처리 대상에 드는 가장 이른 운행일 — 이 날짜 이후(포함)의 {@code moving} 회차만 근접 판정·유실 집계·노선 잠금이 본다. */
    public LocalDate earliestServiceDate() {
        return LocalDate.now(clock).minusDays(GRACE_DAYS);
    }
}
