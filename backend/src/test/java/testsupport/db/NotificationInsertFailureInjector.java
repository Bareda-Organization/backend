package testsupport.db;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 특정 학원의 {@code notification_log} 적재가 DB 에서 실패하게 만든다 — "알림 적재 실패가 그 건의 트랜잭션만 되돌리고 옆 건을
 * 막지 않는다" 를 시험할 때 쓴다. 같은 {@code dedup_key} 는 이제 건너뛰므로(Ruling 622) 키 선점으로는 실패를 만들 수 없고,
 * 아웃박스는 {@code MANDATORY} 프록시라 목 객체로 감싸면 스텁 호출 자체가 트랜잭션 없이 거절된다. 그래서 DB 트리거를 쓴다 —
 * 다른 학원의 INSERT 는 그대로 통과한다. 시험이 끝나면 {@link #clear} 로 지운다.
 */
public final class NotificationInsertFailureInjector {

    private static final String TRIGGER = "r46_fail_notification_insert";

    private NotificationInsertFailureInjector() {
    }

    /** {@code academyId} 학원의 알림 INSERT 만 예외로 거절하는 트리거를 건다. */
    public static void failFor(JdbcTemplate jdbcTemplate, long academyId) {
        clear(jdbcTemplate);
        jdbcTemplate.execute("CREATE FUNCTION " + TRIGGER + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.academy_id = " + academyId + " THEN RAISE EXCEPTION 'injected notification failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbcTemplate.execute("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON notification_log "
                + "FOR EACH ROW EXECUTE FUNCTION " + TRIGGER + "()");
    }

    /** 걸어 둔 트리거와 함수를 지운다 — 걸려 있지 않아도 안전하다. */
    public static void clear(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER + " ON notification_log");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TRIGGER + "()");
    }
}
