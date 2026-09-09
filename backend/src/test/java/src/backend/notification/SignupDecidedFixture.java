package src.backend.notification;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가입 승인 알림 테스트 3종이 공유하는 <b>대기 요청 만들기·지우기</b> 도우미.
 *
 * <p>시드의 대기 요청(요청 1·2)을 쓰지 않는 이유는 이 축의 테스트가 {@code @Transactional} 을 쓸 수
 * 없기 때문이다 — 즉시 발송이 {@code AFTER_COMMIT} 이라 커밋이 실제로 일어나야 한다. 커밋하는
 * 테스트가 시드를 마감시키면 시드를 읽는 다른 테스트가 실행 순서에 따라 갈린다.
 *
 * <p>관계자 요청을 <b>새 학원</b>에 두는 것도 같은 이유다 — 시드 학원 A 에는 재직 관계자가 이미
 * 있어 승인이 {@code STAFF_QUOTA_EXCEEDED} 로 막힌다.
 */
final class SignupDecidedFixture {

    /** 이 축이 만드는 학원 코드 — 뒷정리가 이 값 하나로 전부 걷어낸다. */
    static final String ACADEMY_CODE = "P4T1NTF";

    /** 시드 학원 A — 관계자 승인 축(§5.2)의 요청은 재직 관계자가 있는 이 학원에 둔다. */
    static final long SEED_ACADEMY_A = 1L;

    /** 시드 학원 A 의 재직 관계자 계정 — 관계자 승인 축의 요청 주체다. */
    static final long SEED_STAFF_ACCOUNT = 2L;

    /** 시드 메인 관리자 계정 — 관계자 가입 승인 축(§6.5)의 요청 주체다. */
    static final long SEED_SYSTEM_ADMIN_ACCOUNT = 1L;

    /** 시드 학원 A 의 학생 — 학부모 수락 시 연결 대상(AUTH-11)이다. */
    static final long SEED_STUDENT_A1 = 1L;

    private final JdbcTemplate jdbcTemplate;

    SignupDecidedFixture(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 재직 관계자가 부재한 학원을 만든다 — 관계자 가입 수락이 정원에 막히지 않게 하는 재료다. */
    long 학원을_만든다() {
        jdbcTemplate.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, ?, 'active')",
                ACADEMY_CODE, "P4T1알림학원", "세종");
        return jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, ACADEMY_CODE);
    }

    /** 대기 계정과 그 계정의 대기 가입 요청을 함께 만들고 <b>요청 식별자</b>를 준다. */
    long 대기_요청을_만든다(long academyId, String loginId, String name, String phone, String role,
            String approverType) {
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (?, ?, 'x', ?, ?, CAST(? AS varchar), 'pending')
                """, academyId, loginId, name, phone, role);
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status,
                                            requested_at)
                VALUES ((SELECT id FROM account WHERE login_id = ?), ?, CAST(? AS varchar),
                        CAST(? AS varchar), 'pending', now())
                """, loginId, academyId, role, approverType);
        return jdbcTemplate.queryForObject(
                "SELECT r.id FROM signup_request r JOIN account a ON a.id = r.account_id WHERE a.login_id = ?",
                Long.class, loginId);
    }

    long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    /**
     * 이 축이 만든 행을 전부 지운다 — 자식부터 부모 순서다.
     *
     * <p>{@code notification_log} 를 <b>로그인 아이디 접두사</b>로 지우는 이유는 알림 행이 계정을 FK
     * 로 참조하지 않아(ERD §4.2) 계정을 지워도 남기 때문이다.
     */
    void 뒷정리한다(String loginIdPrefix) {
        jdbcTemplate.update("""
                DELETE FROM notification_log WHERE recipient_account_id IN
                    (SELECT id FROM account WHERE login_id LIKE ?)
                """, loginIdPrefix + "%");
        jdbcTemplate.update("""
                DELETE FROM guardian_student WHERE guardian_id IN
                    (SELECT id FROM guardian WHERE account_id IN
                        (SELECT id FROM account WHERE login_id LIKE ?))
                """, loginIdPrefix + "%");
        jdbcTemplate.update("DELETE FROM guardian WHERE account_id IN "
                + "(SELECT id FROM account WHERE login_id LIKE ?)", loginIdPrefix + "%");
        jdbcTemplate.update("DELETE FROM academy_staff WHERE account_id IN "
                + "(SELECT id FROM account WHERE login_id LIKE ?)", loginIdPrefix + "%");
        jdbcTemplate.update("DELETE FROM signup_request WHERE account_id IN "
                + "(SELECT id FROM account WHERE login_id LIKE ?)", loginIdPrefix + "%");
        jdbcTemplate.update("DELETE FROM account WHERE login_id LIKE ?", loginIdPrefix + "%");
        jdbcTemplate.update("DELETE FROM academy WHERE code = ?", ACADEMY_CODE);
    }
}
