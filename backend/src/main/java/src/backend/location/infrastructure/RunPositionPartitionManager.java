package src.backend.location.infrastructure;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code run_position} 일 단위 범위 파티션의 <b>미리 만들기</b>와 <b>만료 DROP</b>을 맡는다(R46-LATERBE B-1, Ruling 670 ·
 * ERD §7.3). 파티션 이름은 {@code run_position_pYYYYMMDD}(한국 시간 날짜), 범위는 그날 0시 이상 다음날 0시 미만이다.
 *
 * <p><b>기본 파티션({@code run_position_default})이 안전망이다</b> — 해당 날짜 파티션이 없어도 INSERT 는 실패하지 않고 위치
 * 수신이 이어진다. 대신 PostgreSQL 은 기본 파티션에 이미 그 범위의 행이 있으면 새 파티션 생성을 거절하므로,
 * {@link #ensureAhead} 는 그 범위 행을 같은 트랜잭션에서 id 를 보존한 채 새 파티션으로 옮긴다.
 *
 * <p>{@code CREATE TABLE … PARTITION OF} · {@code DROP TABLE} 은 부모 테이블에 짧은 배타 잠금을 건다(밀리초 — 운영은
 * {@code lock_timeout 5s}). 하루 한 번이라 위치 수신을 막는 시간은 무시할 만하다. 두 호출이 겹치지 않게 advisory 잠금을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPositionPartitionManager {

    private static final String PARENT = "run_position";

    private static final String DEFAULT_PARTITION = "run_position_default";

    private static final String NAME_PREFIX = "run_position_p";

    /** 파티션 경계는 서비스 기준 시간대(ERD §2)의 자정이다 — 이름의 날짜와 경계가 같은 시계를 본다. */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");

    /** 파티션 생성끼리 직렬화하는 advisory 잠금 키(임의의 고정값). */
    private static final long ADVISORY_LOCK_KEY = 4_600_001L;

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactionTemplate;

    /**
     * {@code today} 부터 {@code lookAheadDays} 일 뒤까지 없는 일 파티션을 만든다 — 만든 개수를 돌려준다(이미 있으면 0).
     * 한 날짜의 생성이 실패해도 뒤 날짜를 이어 만들고, 실패는 끝에서 첫 예외로 던진다(나머지는 suppressed) — 호출자의 실패
     * 카운터와 "마지막 성공 이후 경과" 게이지가 그대로 실패를 드러낸다(BR-340).
     */
    public int ensureAhead(LocalDate today, int lookAheadDays) {
        int created = 0;
        RuntimeException failure = null;
        for (int offset = 0; offset <= lookAheadDays; offset++) {
            try {
                if (ensurePartition(today.plusDays(offset))) {
                    created++;
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return created;
    }

    /**
     * 상한이 {@code cutoff} 이하인 일 파티션을 통째로 지우고, 기본 파티션의 {@code cutoff} 이전 행을 지운다 — 지운 파티션 수를
     * 돌려준다. {@code cutoff} 가 걸친 날의 파티션은 남는다(그 날 행은 최대 하루 더 보관 — Ruling 670). 이름 규칙이 다른
     * 파티션은 건드리지 않는다.
     */
    public int dropExpired(OffsetDateTime cutoff) {
        int dropped = 0;
        for (String name : partitionNames()) {
            LocalDate day = dayOf(name);
            if (day != null && !upperBoundOf(day).isAfter(cutoff)) {
                jdbc.execute("DROP TABLE IF EXISTS " + name);
                dropped++;
            }
        }
        int strayRows = jdbc.update("DELETE FROM " + DEFAULT_PARTITION + " WHERE recorded_at < ?", cutoff);
        if (dropped > 0 || strayRows > 0) {
            log.info("위치 이력 만료 — 파티션 {}개 DROP · 기본 파티션 {}행 삭제", dropped, strayRows);
        }
        return dropped;
    }

    private boolean ensurePartition(LocalDate day) {
        if (partitionNames().contains(nameOf(day))) {
            return false;
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> createLocked(day)));
    }

    /** 잠금 안에서 한 번 더 확인하고 만든다 — 기본 파티션에 그 범위 행이 있으면 먼저 빼 두었다가 새 파티션에 넣는다. */
    private boolean createLocked(LocalDate day) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ADVISORY_LOCK_KEY);
        if (partitionNames().contains(nameOf(day))) {
            return false;
        }
        String lower = boundOf(day);
        String upper = boundOf(day.plusDays(1));
        // 부모 잠금을 먼저 잡는다 — 진행 중이던 위치 INSERT 가 커밋될 때까지 기다린 뒤 기본 파티션을 읽어야, 그 사이 커밋된
        // 같은 날짜 행이 이전에서 빠져 CREATE 가 "기본 파티션 제약 위반" 으로 실패하지 않는다(BR-340)
        jdbc.execute("LOCK TABLE ONLY " + PARENT + " IN ACCESS EXCLUSIVE MODE");
        jdbc.execute("""
                CREATE TEMP TABLE run_position_moved ON COMMIT DROP AS
                WITH moved AS (
                    DELETE FROM %s WHERE recorded_at >= '%s' AND recorded_at < '%s' RETURNING *
                ) SELECT * FROM moved
                """.formatted(DEFAULT_PARTITION, lower, upper));
        jdbc.execute("CREATE TABLE %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                .formatted(nameOf(day), PARENT, lower, upper));
        int moved = jdbc.update("INSERT INTO " + PARENT + " OVERRIDING SYSTEM VALUE SELECT * FROM run_position_moved");
        // 바깥 트랜잭션 안에서 여러 날을 이어 만들어도 임시 테이블 이름이 겹치지 않게 바로 치운다
        jdbc.execute("DROP TABLE run_position_moved");
        log.info("위치 이력 파티션 생성 — {}{}", nameOf(day), moved > 0 ? " (기본 파티션 " + moved + "행 이전)" : "");
        return true;
    }

    private List<String> partitionNames() {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'run_position'::regclass
                """, String.class);
    }

    private static String nameOf(LocalDate day) {
        return NAME_PREFIX + NAME_DATE.format(day);
    }

    /** 우리 이름 규칙({@code run_position_pYYYYMMDD})의 파티션이면 그 날짜, 아니면 {@code null}. */
    private static LocalDate dayOf(String partitionName) {
        if (!partitionName.matches(NAME_PREFIX + "\\d{8}")) {
            return null;
        }
        return LocalDate.parse(partitionName.substring(NAME_PREFIX.length()), NAME_DATE);
    }

    private static OffsetDateTime upperBoundOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(SERVICE_ZONE).toOffsetDateTime();
    }

    private static String boundOf(LocalDate day) {
        return BOUND_FORMAT.format(day.atStartOfDay(SERVICE_ZONE));
    }
}
