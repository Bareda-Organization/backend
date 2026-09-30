package src.backend.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.location.entity.RunPosition;

/**
 * Redis 장애 대체 조회 {@code findLatestByRunIdIn} — 옛 {@code DISTINCT ON} 쿼리와 같은 행을 내고(기록 시각이 같은
 * 동률은 id 가 큰 쪽), 회차의 전 행을 정렬하지 않는다(R46 D #14). 시험 트랜잭션이 롤백해 행이 남지 않는다.
 */
@SpringBootTest
@Transactional
class RunPositionRepositoryLatestTest {

    private static final long RUN_A = 3L;

    private static final long RUN_B = 4L;

    private static final String OLD_QUERY = """
            SELECT DISTINCT ON (run_id) id FROM run_position WHERE run_id IN (?, ?)
            ORDER BY run_id, recorded_at DESC, id DESC
            """;

    @Autowired
    private RunPositionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("회차별 최신 1행이 옛 DISTINCT ON 쿼리와 같고 기록 시각 동률은 id 큰 쪽이다")
    void 회차별_최신_1행이_옛_쿼리와_같다() {
        OffsetDateTime base = OffsetDateTime.parse("2099-01-01T00:00:00Z");
        행을_심는다(RUN_A, base);
        행을_심는다(RUN_A, base.plusMinutes(5));
        long tieFirst = 행을_심는다(RUN_A, base.plusMinutes(9));
        long tieLast = 행을_심는다(RUN_A, base.plusMinutes(9));
        행을_심는다(RUN_B, base.plusMinutes(1));
        long bLatest = 행을_심는다(RUN_B, base.plusMinutes(7));
        행을_심는다(RUN_B, base.plusMinutes(2));

        List<Long> actual = repository.findLatestByRunIdIn(List.of(RUN_A, RUN_B)).stream()
                .map(RunPosition::getId).sorted().collect(Collectors.toList());
        List<Long> old = jdbc.queryForList(OLD_QUERY, Long.class, RUN_A, RUN_B).stream().sorted().toList();

        assertThat(tieLast).isGreaterThan(tieFirst);
        assertThat(actual).containsExactlyInAnyOrder(tieLast, bLatest).isEqualTo(old);
    }

    @Test
    @DisplayName("회차 행이 수천 개여도 실행 계획이 회차 인덱스의 정렬을 그대로 쓴다(전체 정렬 없음)")
    void 실행_계획에_전체_정렬이_없다() {
        jdbc.update("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                SELECT ?, 37.5, 127.0, TIMESTAMPTZ '2099-02-01' + n * INTERVAL '1 second', now()
                FROM generate_series(1, 2000) n
                """, RUN_A);
        jdbc.update("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                SELECT r, 37.5, 127.0, TIMESTAMPTZ '2099-02-01' + n * INTERVAL '1 second', now()
                FROM generate_series(1, 2000) n, unnest(ARRAY[4, 5, 6]) r
                """);
        jdbc.execute("ANALYZE run_position");

        String sql = 조회_쿼리_원문();
        String plan = jdbc.queryForList("EXPLAIN " + sql.replace(":runIds", "3, 4"), String.class).stream()
                .collect(Collectors.joining("\n"));

        // 정렬 키 중 recorded_at 은 인덱스가 이미 정렬해 둔 것(Presorted) — 동률 묶음만 id 로 다시 정렬한다
        assertThat(plan).as(plan).contains("ix_run_position_run_recorded", "Presorted Key: run_position.recorded_at");
    }

    /** 운영 쿼리 원문 — 시험용 사본이 아니라 애너테이션에서 그대로 읽어 쿼리가 바뀌면 이 시험도 따라간다. */
    private static String 조회_쿼리_원문() {
        try {
            return RunPositionRepository.class.getMethod("findLatestByRunIdIn", java.util.Collection.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private long 행을_심는다(long runId, OffsetDateTime recordedAt) {
        return jdbc.queryForObject("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (?, 37.5, 127.0, ?, now()) RETURNING id
                """, Long.class, runId, recordedAt);
    }
}
