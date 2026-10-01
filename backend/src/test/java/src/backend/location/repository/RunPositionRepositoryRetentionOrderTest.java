package src.backend.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보존 정리 후보 {@code findIdsForRetentionCleanup} 의 정렬 키가 인덱스 {@code ix_run_position_retention_cutoff (recorded_at)}
 * 의 키와 같다(R46 I-02). {@code order by id} 이면 컷오프가 정상 상태처럼 전체의 약 1% 만 거를 때 플래너가 "컷오프 이전
 * 전체를 읽어 id 로 정렬" 하는 계획을 골라, 5,000행 배치마다 그 전체를 다시 읽는다 — {@code recorded_at} 순이면 인덱스가
 * 배치 크기만큼만 읽는다. 운영에서는 id 순서와 시각 순서가 거의 같아 결과만으로는 구별되지 않으므로, 시험은 둘을
 * <b>일부러 거꾸로</b> 심는다(먼저 들어간 행이 더 최근). 시험 트랜잭션이 롤백해 행이 남지 않는다.
 */
@SpringBootTest
@Transactional
class RunPositionRepositoryRetentionOrderTest {

    /** 어떤 시드·다른 시험 행보다도 오래된 시각 — 심은 행만 컷오프 이전에 든다. */
    private static final OffsetDateTime BASE = OffsetDateTime.of(2001, 1, 10, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private RunPositionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 보존_정리_후보는_id_가_아니라_기록_시각이_오래된_순으로_고른다() {
        long newest = insertAt(BASE.plusDays(9));
        long middle = insertAt(BASE.plusDays(5));
        long oldest = insertAt(BASE);

        List<Long> picked = repository.findIdsForRetentionCleanup(BASE.plusDays(30), Limit.of(2));

        assertThat(picked).as("id 는 newest < middle < oldest 순이다 — id 순이면 [newest, middle] 이 나온다")
                .containsExactly(oldest, middle);
        assertThat(newest).isLessThan(middle).isLessThan(oldest);
    }

    private long insertAt(OffsetDateTime recordedAt) {
        return jdbc.queryForObject("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (999991, 37.5, 127.0, ?, ?) RETURNING id
                """, Long.class, recordedAt, recordedAt);
    }
}
