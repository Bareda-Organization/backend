package src.backend.global.retention;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 멱등 키({@code client_key})를 회차 종료 24시간 뒤에 비운다(API_SPEC §1.7, R56 Ruling 894 ⑥) — 키를 가진 4개 테이블
 * ({@code rider_status_history} · {@code emergency_alert} · {@code exception_report} · {@code no_show_contact})의 행은 무기한
 * 보존 대상이라 <b>행은 지우지 않고 키 값만 {@code NULL} 로</b> 만든다(UNIQUE 는 NULL 을 여럿 허용한다).
 *
 * <p>대상은 그 행의 회차가 {@code finished_at}(미종료 회차는 {@code depart_time}) 기준으로 {@code cutoff} 보다 이전에 끝난 행이다 —
 * 키의 기한이 행을 만든 시각이 아니라 <b>회차 종료</b>를 기준으로 하는 사양("최소 회차 종료 후 24시간 보장")을 지킨다.
 *
 * <p>테이블마다 한 문장짜리 벌크 UPDATE 를 {@code batchSize} 행씩 반복한다({@link RetentionPolicy#BATCH_SIZE} 와 같은 이유 — 행 잠금을 길게
 * 붙들지 않는다). 문장마다 독립된 트랜잭션이다(트랜잭션 없는 {@link JdbcTemplate} 은 문장별 자동 커밋). ponytail: 키 컬럼에 부분 인덱스가
 * 없어 정리 때 후보를 훑는다 — 느려지면 {@code (client_key) WHERE client_key IS NOT NULL} 인덱스를 더한다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyKeyExpiry {

    /** 키를 가진 테이블별로 회차를 찾아가는 경로 — 각 SQL 의 첫 {@code ?} 는 기준 시각, 둘째는 묶음 크기다. */
    private static final Map<String, String> RUN_FINISHED_OF_TABLE = new LinkedHashMap<>();

    static {
        RUN_FINISHED_OF_TABLE.put("rider_status_history", """
                JOIN run_rider rr ON rr.id = t.run_rider_id JOIN run r ON r.id = rr.run_id""");
        RUN_FINISHED_OF_TABLE.put("emergency_alert", "JOIN run r ON r.id = t.run_id");
        RUN_FINISHED_OF_TABLE.put("exception_report", "JOIN run r ON r.id = t.run_id");
        RUN_FINISHED_OF_TABLE.put("no_show_contact", """
                JOIN no_show_case c ON c.id = t.no_show_case_id JOIN run_rider rr ON rr.id = c.run_rider_id \
                JOIN run r ON r.id = rr.run_id""");
    }

    private final JdbcTemplate jdbc;

    /**
     * 기한이 지난 키를 비운다.
     *
     * @param cutoff 이보다 이전에 끝난 회차의 행이 대상이다
     * @return 비운 행 수(네 테이블 합계)
     */
    public int expire(OffsetDateTime cutoff, int batchSize) {
        int total = 0;
        for (Map.Entry<String, String> table : RUN_FINISHED_OF_TABLE.entrySet()) {
            String sql = "UPDATE " + table.getKey() + " SET client_key = NULL WHERE id IN (SELECT t.id FROM "
                    + table.getKey() + " t " + table.getValue()
                    + " WHERE t.client_key IS NOT NULL AND COALESCE(r.finished_at, r.depart_time) < ?"
                    + " ORDER BY t.id LIMIT ?)";
            int updated;
            do {
                updated = jdbc.update(sql, cutoff, batchSize);
                total += updated;
            } while (updated == batchSize);
        }
        return total;
    }
}
