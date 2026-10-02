package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code run_position} 일 단위 범위 파티션의 미리 만들기 · 기본 파티션 행 이전 · 만료 DROP(R46-LATERBE B-1, Ruling 670).
 *
 * <p>실제 DB 에 파티션을 만들고 지우므로 <b>서로 다른 먼 연도</b>(2003 · 2004 · 2005 · 2006 · 2007)를 시험마다 따로 쓴다 — 지금 날짜 근처의
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

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TABLE IF EXISTS run_position_p20060302"); // 파티션이 아닌 같은 이름 테이블 — 생성 실패를 심은 시험의 흔적
        for (int year : new int[] { 2003, 2004, 2005, 2006, 2007 }) {
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
    @DisplayName("한 날짜의 생성이 실패해도 뒤 날짜를 계속 만들고, 실패는 끝에서 호출자에게 알린다(BR-340)")
    void 한_날짜가_실패해도_뒤_날짜를_계속_만든다() {
        // 같은 이름의 일반 테이블이 이미 있어 둘째 날(3/2) 파티션 생성만 실패한다
        jdbc.execute("CREATE TABLE run_position_p20060302 (id int)");

        assertThatThrownBy(() -> manager.ensureAhead(LocalDate.of(2006, 3, 1), 3))
                .as("호출자(스케줄러·애스펙트)가 실패 카운터를 올릴 수 있게 끝에서 던진다")
                .isInstanceOf(RuntimeException.class);

        assertThat(partitionNames(2006)).as("실패한 날 앞뒤 날짜는 모두 만들어졌다").containsExactly(
                "run_position_p20060301", "run_position_p20060303", "run_position_p20060304");
    }

    @Test
    @DisplayName("기본 파티션으로 들어가는 위치 INSERT 가 생성 도중 커밋돼도 그 행까지 새 파티션으로 옮기며 생성에 성공한다(BR-340)")
    void 생성_도중_커밋된_위치_행도_옮기며_생성은_실패하지_않는다() throws Exception {
        LocalDate day = LocalDate.of(2007, 4, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long inflightId;
        Future<Integer> creating;
        try (Connection inflight = dataSource.getConnection()) {
            inflight.setAutoCommit(false);
            // 기본 파티션으로 가는 INSERT 가 아직 커밋 전이다 — 부모 테이블 행 잠금을 쥐고 있다
            inflightId = insertOn(inflight, OffsetDateTime.of(2007, 4, 1, 10, 0, 0, 0, KST));

            creating = executor.submit(() -> manager.ensureAhead(day, 0));
            waitUntilSomeoneWaitsForLock();
            inflight.commit(); // 파티션 생성이 부모 잠금을 기다리는 사이 그 날짜 행이 기본 파티션에 커밋된다
        } finally {
            executor.shutdown();
        }

        assertThat(creating.get(30, TimeUnit.SECONDS)).as("생성에 성공한다").isEqualTo(1);
        assertThat(partitionOf(inflightId)).as("늦게 커밋된 행도 새 파티션으로 옮겨졌다").isEqualTo("run_position_p20070401");
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

    /** 다른 세션이 잠금을 기다리는 상태가 될 때까지 기다린다 — 파티션 생성이 진행 중 INSERT 뒤에 줄 서는 순간을 고정한다. */
    private void waitUntilSomeoneWaitsForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("파티션 생성이 잠금을 기다리는 상태가 20초 안에 오지 않았다");
    }

    private long insertOn(Connection connection, OffsetDateTime recordedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at)
                VALUES (999991, 37.5, 127.0, ?, ?) RETURNING id
                """)) {
            statement.setObject(1, recordedAt);
            statement.setObject(2, recordedAt);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
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
