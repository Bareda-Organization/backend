package src.backend.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.location.entity.RunPosition;

/**
 * Redis 가 비어 있을 때의 대체 조회 {@code findLatestMovingByRunIdIn} — 운행 중({@code moving}) 회차의 최신 행만 읽는다. 시드 회차 3 은 운행 중, 1 은 대기 중이다.
 * 시험 트랜잭션이 롤백해 행이 남지 않는다.
 */
@SpringBootTest
@Transactional
class RunPositionRepositoryMovingLatestTest {

    private static final long MOVING_RUN = 3L;

    private static final long IDLE_RUN = 1L;

    @Autowired
    private RunPositionRepository repository;

    @Test
    @DisplayName("운행 중 회차의 구간 안 최신 행만 읽고, 대기 중 회차와 구간 밖 행은 읽지 않는다")
    void 운행_중_회차의_구간_안_최신_행만_읽는다() {
        OffsetDateTime now = OffsetDateTime.now();
        save(MOVING_RUN, now.minusMinutes(10), "37.1");
        long recent = save(MOVING_RUN, now.minusSeconds(30), "37.2");
        save(IDLE_RUN, now.minusSeconds(30), "37.3");

        List<RunPosition> found = repository.findLatestMovingByRunIdIn(List.of(MOVING_RUN, IDLE_RUN), now.minusMinutes(2), now.plusMinutes(10));

        assertThat(found).extracting(RunPosition::getId).as("구간 안 최신 행 하나 — 대기 중 회차는 없음").containsExactly(recent);
        assertThat(repository.findLatestMovingByRunIdIn(List.of(MOVING_RUN), now.minusSeconds(10), now.plusMinutes(10)))
                .as("구간 안에 행이 없으면 빈 값").isEmpty();
    }

    private long save(long runId, OffsetDateTime recordedAt, String lat) {
        return repository.save(RunPosition.onReceive(runId, new BigDecimal(lat), new BigDecimal("127.0"), recordedAt, recordedAt, null, null))
                .getId();
    }
}
