package src.backend.global.retention;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 종료된 회차를 {@code 409 RUN_NOT_MOVING} 으로 거절하는 두 표의 멱등 키({@code client_key})를 회차 종료 24시간 뒤에 비운다
 * (API_SPEC §1.7, Ruling 894 ⑥) — {@code rider_status_history}(승하차 처리) · {@code no_show_contact}(미승차 연락 기록).
 * 두 표의 행은 무기한 보존 대상이라 <b>행은 지우지 않고 키 값만 {@code NULL} 로</b> 만든다(UNIQUE 는 NULL 을 여럿 허용한다).
 *
 * <p><b>비상 발신({@code emergency_alert}) · 예외 보고({@code exception_report}) 키는 비우지 않는다.</b> 두 요청은 종료된 회차도 받아서,
 * 키를 비운 뒤 늦게 도착한 재전송이 새 행(비상 알림 재발송)을 만든다. 행이 드물어 영구 보존의 부담이 작다.
 *
 * <p>대상은 그 행의 회차가 {@code finished_at}(미종료 회차는 {@code depart_time}) 기준으로 {@code cutoff} 보다 이전에 끝난 행이다 —
 * 키의 기한이 행을 만든 시각이 아니라 <b>회차 종료</b>를 기준으로 하는 사양("최소 회차 종료 후 24시간 보장")을 지킨다.
 *
 * <p>표마다 한 문장짜리 벌크 UPDATE 를 {@code batchSize} 행씩 반복한다({@link RetentionPolicy#BATCH_SIZE} 와 같은 이유 — 행 잠금을 길게
 * 붙들지 않는다). 문장마다 독립된 트랜잭션이다({@link RetentionTransactions} — 문장 시간 상한이 풀린다). ponytail: 키 컬럼에 부분 인덱스가
 * 없어 정리 때 후보를 훑는다 — 느려지면 {@code (client_key) WHERE client_key IS NOT NULL} 인덱스를 더한다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyKeyExpiry {

    /** 첫 {@code ?} 는 기준 시각, 둘째는 묶음 크기다. */
    private static final String CLEAR_RIDER_STATUS_HISTORY = """
            UPDATE rider_status_history SET client_key = NULL WHERE id IN (
                SELECT t.id FROM rider_status_history t
                JOIN run_rider rr ON rr.id = t.run_rider_id JOIN run r ON r.id = rr.run_id
                WHERE t.client_key IS NOT NULL AND COALESCE(r.finished_at, r.depart_time) < ?
                ORDER BY t.id LIMIT ?)""";

    private static final String CLEAR_NO_SHOW_CONTACT = """
            UPDATE no_show_contact SET client_key = NULL WHERE id IN (
                SELECT t.id FROM no_show_contact t
                JOIN no_show_case c ON c.id = t.no_show_case_id
                JOIN run_rider rr ON rr.id = c.run_rider_id JOIN run r ON r.id = rr.run_id
                WHERE t.client_key IS NOT NULL AND COALESCE(r.finished_at, r.depart_time) < ?
                ORDER BY t.id LIMIT ?)""";

    private final JdbcTemplate jdbc;

    private final RetentionTransactions retentionTransactions;

    /**
     * 기한이 지난 키를 비운다.
     *
     * @param cutoff 이보다 이전에 끝난 회차의 행이 대상이다
     * @return 비운 행 수(두 표 합계)
     */
    public int expire(OffsetDateTime cutoff, int batchSize) {
        return clear(CLEAR_RIDER_STATUS_HISTORY, cutoff, batchSize) + clear(CLEAR_NO_SHOW_CONTACT, cutoff, batchSize);
    }

    private int clear(String sql, OffsetDateTime cutoff, int batchSize) {
        int total = 0;
        int updated;
        do {
            updated = retentionTransactions.call(() -> jdbc.update(sql, cutoff, batchSize));
            total += updated;
        } while (updated == batchSize);
        return total;
    }
}
