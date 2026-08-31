package src.backend.run.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

/**
 * 운행 시작 가능 시간창 판정(API_SPEC §4.4, RUN-02) — 출발 시각 ±10분(Ruling 202)만 {@code 200} 이다.
 *
 * <p>10분은 <b>코드 상수</b>다. {@code application.yml}·DB 로 옮기지 않는다 — 창을 여닫는 기준이
 * 운영 정책이 아니라 "너무 이르거나 너무 늦은 시작을 걸러내는 안전 여유"라, 설정으로 빼면 실수로
 * 창을 넓히거나 좁히는 배포가 코드 리뷰 없이 나갈 수 있다.
 *
 * <p>경계는 <b>양끝 포함</b>이다 — 출발 정확히 10분 전·10분 후는 통과다. {@code isBefore}·
 * {@code isAfter} 만으로 짜면 경계 순간이 반대로 뒤집히기 쉬워, 부정(negation) 형태로 both-inclusive
 * 를 명시한다.
 */
@Component
public class RunStartWindowPolicy {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** {@code now} 가 {@code departTime} 의 ±10분 창 안(양끝 포함)인지 — 밖이면 호출부가 403 으로 답한다. */
    public boolean isWithinWindow(OffsetDateTime departTime, OffsetDateTime now) {
        OffsetDateTime earliest = departTime.minus(WINDOW);
        OffsetDateTime latest = departTime.plus(WINDOW);
        return !now.isBefore(earliest) && !now.isAfter(latest);
    }
}
