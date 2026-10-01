package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code run_position} 일 단위 범위 파티션의 미리 만들기 · 기본 파티션 행 이전 · 만료 DROP(R46-LATERBE B-1, Ruling 670).
 *
 * <p>실제 DB 에 파티션을 만들고 지우므로 <b>서로 다른 먼 연도</b>(2003 · 2004 · 2005)를 시험마다 따로 쓴다 — 지금 날짜 근처의
 * 파티션이나 다른 시험이 심은 행을 건드리지 않고(컷오프가 2005 년 안이라 그 뒤 파티션은 대상이 아니다), 시험 뒤 만든 파티션과
 * 행을 직접 지운다. 일 파티션 경계는 한국 시간 자정이다.
 */
@SpringBootTest
class RunPositionPartitionManagerTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Autowired
    private RunPositionPartitionManager manager;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        for (int year : new int[] { 2003, 2004, 2005 }) {
            jdbc.queryForList("""
                    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = 'run_position'::regclass AND c.relname LIKE ?
                    """, String.class, "run_position_p" + year + "%")
                    .forEach(name -> jdbc.execute("DROP TABLE IF EXISTS " + name));
            jdbc.update("DELETE FROM run_position WHERE recorded_at >= ? AND recorded_at < ?",
                    OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, KST), OffsetDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, KST));
        }
    }

    @Test
    @DisplayName("미리 만들기 — 오늘부터 N일 뒤까지 일 파티션이 생기고 두 번 불러도 같다")
    void 지정한_날짜부터_N일_뒤까지_파티션을_만든다() {
        LocalDate day = LocalDate.of(2003, 3, 1);

        int first = manager.ensureAhead(day, 3);
        int second = manager.ensureAhead(day, 3);

        assertThat(first).as("0~3일 뒤 = 4개").isEqualTo(4);
        assertThat(second).as("이미 있으면 만들지 않는다").isZero();
        assertThat(partitionNames(2003)).containsExactly(
                "run_position_p20030301", "run_position_p20030302", "run_position_p20030303", "run_position_p20030304");
    }

    @Test
    @DisplayName("만든 파티션에는 한국 시간 그날 0시~24시 행이 들어가고 경계 밖 행은 기본 파티션으로 간다")
    void 행은_한국_시간_날짜의_파티션으로_들어간다() {
        manager.ensureAhead(LocalDate.of(2003, 3, 1), 0);

        long inside = insertAt(OffsetDateTime.of(2003, 3, 1, 23, 59, 59, 0, KST));
        long justBefore = insertAt(OffsetDateTime.of(2003, 2, 28, 23, 59, 59, 0, KST));
        long justAfter = insertAt(OffsetDateTime.of(2003, 3, 2, 0, 0, 0, 0, KST));

        assertThat(partitionOf(inside)).isEqualTo("run_position_p20030301");
        assertThat(partitionOf(justBefore)).as("파티션이 없는 날짜도 INSERT 는 성공한다").isEqualTo("run_position_default");
        assertThat(partitionOf(justAfter)).isEqualTo("run_position_default");
    }

    @Test
    @DisplayName("기본 파티션에 이미 쌓인 그 날짜의 행은 파티션을 만들 때 id 를 보존한 채 새 파티션으로 옮겨진다")
    void 기본_파티션에_쌓인_행은_새_파티션으로_옮겨진다() {
        long inDefault = insertAt(OffsetDateTime.of(2004, 5, 1, 10, 0, 0, 0, KST));
        long otherDay = insertAt(OffsetDateTime.of(2004, 5, 2, 10, 0, 0, 0, KST));
        assertThat(partitionOf(inDefault)).isEqualTo("run_position_default");

        manager.ensureAhead(LocalDate.of(2004, 5, 1), 0);

        assertThat(partitionOf(inDefault)).as("같은 id 가 새 파티션에 있다").isEqualTo("run_position_p20040501");
        assertThat(partitionOf(otherDay)).as("다른 날짜 행은 기본 파티션에 그대로").isEqualTo("run_position_default");
    }

    @Test
    @DisplayName("만료 DROP — 상한이 컷오프 이하인 일 파티션만 지우고 컷오프가 걸친 날·이후 날·이름이 다른 파티션은 남긴다")
    void 만료된_파티션만_통째로_지운다() {
        manager.ensureAhead(LocalDate.of(2005, 3, 1), 4);
        jdbc.execute("CREATE TABLE run_position_p2005manual PARTITION OF run_position FOR VALUES FROM ('2005-06-01 00:00:00+09') TO ('2005-06-02 00:00:00+09')");
        long expired = insertAt(OffsetDateTime.of(2005, 3, 1, 12, 0, 0, 0, KST));
        long straddling = insertAt(OffsetDateTime.of(2005, 3, 3, 8, 0, 0, 0, KST));

        int dropped = manager.dropExpired(OffsetDateTime.of(2005, 3, 3, 10, 0, 0, 0, KST));

        assertThat(dropped).as("3/1 · 3/2 파티션").isEqualTo(2);
        assertThat(partitionNames(2005)).containsExactly(
                "run_position_p20050303", "run_position_p20050304", "run_position_p20050305", "run_position_p2005manual");
        assertThat(exists(expired)).as("지워진 파티션의 행").isFalse();
        assertThat(exists(straddling)).as("컷오프가 걸친 날의 파티션은 통째로 남는다(최대 하루 더 보관 — Ruling 670)").isTrue();
    }

    @Test
    @DisplayName("만료 DROP — 기본 파티션에 쌓인 컷오프 이전 행도 지운다(그 기간 파티션이 없던 행)")
    void 기본_파티션의_만료_행도_지운다() {
        long old = insertAt(OffsetDateTime.of(2005, 1, 5, 12, 0, 0, 0, KST));
        long recent = insertAt(OffsetDateTime.of(2005, 2, 5, 12, 0, 0, 0, KST));

        manager.dropExpired(OffsetDateTime.of(2005, 1, 20, 0, 0, 0, 0, KST));

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    private List<String> partitionNames(int year) {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'run_position'::regclass AND c.relname LIKE ? ORDER BY c.relname
                """, String.class, "run_position_p" + year + "%");
    }

    private long insertAt(OffsetDateTime recordedAt) {
        return jdbc.queryForObject("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (999991, 37.5, 127.0, ?, ?) RETURNING id
                """, Long.class, recordedAt, recordedAt);
    }

    private String partitionOf(long id) {
        return jdbc.queryForObject("SELECT tableoid::regclass::text FROM run_position WHERE id = ?", String.class, id);
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM run_position WHERE id = ?", Integer.class, id) > 0;
    }
}
