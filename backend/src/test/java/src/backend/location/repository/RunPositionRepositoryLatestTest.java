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
import src.backend.location.infrastructure.RunPositionPartitionManager;

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
    private RunPositionPartitionManager manager;

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

    /**
     * R46-LATERBE B-1 — 파티션 테이블에서도 엔티티 저장(IDENTITY id 회수)과 최신 1행 조회가 그대로 동작한다. 행이 일 파티션 둘과
     * 기본 파티션에 흩어져 있어도 회차별 최신 행(기록 시각 → id)이 같다. 시험 트랜잭션이 롤백하므로 만든 파티션도 남지 않는다.
     */
    @Test
    @DisplayName("행이 일 파티션 둘과 기본 파티션에 흩어져 있어도 엔티티 저장·최신 1행 조회가 같다")
    void 파티션에_걸쳐_있어도_저장과_최신_조회가_같다() {
        manager.ensureAhead(java.time.LocalDate.of(2099, 3, 1), 1);
        OffsetDateTime day1 = OffsetDateTime.parse("2099-03-01T10:00:00+09:00");
        OffsetDateTime day2 = OffsetDateTime.parse("2099-03-02T10:00:00+09:00");
        행을_심는다(RUN_A, day1);
        행을_심는다(RUN_A, day2);
        long saved = repository.save(RunPosition.onReceive(RUN_A, java.math.BigDecimal.valueOf(37.5),
                java.math.BigDecimal.valueOf(127.0), day2.plusMinutes(30), day2.plusMinutes(30), null, null)).getId();
        long bLatest = 행을_심는다(RUN_B, day1.plusHours(1));

        List<Long> inPartitions = repository.findLatestByRunIdIn(List.of(RUN_A, RUN_B)).stream()
                .map(RunPosition::getId).sorted().toList();
        assertThat(inPartitions).containsExactlyInAnyOrder(saved, bLatest);
        assertThat(jdbc.queryForObject("SELECT tableoid::regclass::text FROM run_position WHERE id = ?", String.class, saved))
                .isEqualTo("run_position_p20990302");

        // 파티션이 없는 날짜(기본 파티션)의 행이 더 최근이면 그 행이 최신이다
        long inDefault = 행을_심는다(RUN_A, OffsetDateTime.parse("2099-03-05T10:00:00+09:00"));
        assertThat(repository.findLatestByRunIdIn(List.of(RUN_A)).stream().map(RunPosition::getId))
                .containsExactly(inDefault);
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
        // 파티션 테이블의 인덱스는 파티션마다 자동 이름("<파티션>_run_id_recorded_at_idx")으로 생긴다(R46-LATERBE B-1)
        assertThat(plan).as(plan).contains("_run_id_recorded_at_idx", "Presorted Key: run_position.recorded_at");
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
