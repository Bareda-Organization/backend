package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;

/**
 * 연결 코드를 동시에 발급해도 같은 번호의 살아 있는 코드가 둘 저장되지 않는다 — "겹치는지 읽고 넣는" 두 단계로는 두 요청이 모두 읽기를
 * 통과해 같은 번호를 넣을 수 있으므로, 중복은 DB 의 유일 제약이 막고 발급 쪽은 그 충돌에서 다시 뽑는다. 두 요청이 첫 6자리를 다 뽑을
 * 때까지 서로 기다리게 해 겹침을 만든다(뽑은 번호가 같고, 둘 다 넣기 전이다).
 */
@SpringBootTest
class LinkCodeConcurrentIssueTest {

    private static final String SAME_FIRST_CODE = "111111";

    @Autowired
    private ChildLinkCommandService childLinkCommandService;

    @Autowired
    private LinkCodeGenerator codeGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Object originalRandom;

    @AfterEach
    void 심은_코드를_지우고_난수를_되돌린다() {
        jdbcTemplate.update("DELETE FROM link_code WHERE student_id IN (SELECT s.id FROM student s "
                + "JOIN account a ON a.id = s.account_id WHERE a.login_id IN (?, ?))",
                SeedFixtures.STUDENT_A4_LOGIN_ID, SeedFixtures.STUDENT_B1_LOGIN_ID);
        if (originalRandom != null) {
            ReflectionTestUtils.setField(AopTestUtils.getUltimateTargetObject(codeGenerator), "random", originalRandom);
        }
    }

    @Test
    void 동시에_발급해도_같은_번호의_살아_있는_코드는_둘이_아니다() throws Exception {
        LinkCodeGenerator target = AopTestUtils.getUltimateTargetObject(codeGenerator);
        originalRandom = ReflectionTestUtils.getField(target, "random");
        CyclicBarrier bothDrew = new CyclicBarrier(2);
        ReflectionTestUtils.setField(target, "random", 같은_첫_번호를_뽑고_기다리는_난수(bothDrew));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> 발급한다(SeedFixtures.STUDENT_A4_LOGIN_ID));
            Future<String> second = pool.submit(() -> 발급한다(SeedFixtures.STUDENT_B1_LOGIN_ID));

            String firstCode = 결과(first);
            String secondCode = 결과(second);

            assertThat(List.of(firstCode, secondCode)).as("두 학생이 받은 코드").doesNotHaveDuplicates();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM (SELECT code FROM link_code WHERE used_at IS NULL AND expires_at >= now() "
                            + "GROUP BY code HAVING count(*) > 1) duplicated", Integer.class))
                    .as("살아 있는 코드 중 번호가 겹치는 것").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private String 발급한다(String loginId) {
        var row = jdbcTemplate.queryForMap("SELECT a.id AS account_id, a.academy_id FROM account a WHERE a.login_id = ?",
                loginId);
        AuthUser student = new AuthUser(((Number) row.get("account_id")).longValue(),
                ((Number) row.get("academy_id")).longValue(), Role.STUDENT, AccountStatus.ACTIVE);
        return childLinkCommandService.issueCode(student).code();
    }

    private static String 결과(Future<String> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(15, TimeUnit.SECONDS);
    }

    /** 스레드마다 첫 6자리가 같고(두 스레드가 모두 뽑은 뒤에야 다음으로 간다), 다시 뽑는 번호는 스레드마다 다르다. */
    private static SecureRandom 같은_첫_번호를_뽑고_기다리는_난수(CyclicBarrier bothDrew) {
        AtomicInteger threadCount = new AtomicInteger();
        return new SecureRandom() {
            private final ThreadLocal<Integer> calls = ThreadLocal.withInitial(() -> 0);

            private final ThreadLocal<Integer> thread = ThreadLocal.withInitial(threadCount::getAndIncrement);

            @Override
            public int nextInt(int bound) {
                int call = calls.get();
                calls.set(call + 1);
                if (call < 6) {
                    if (call == 5) {
                        try {
                            bothDrew.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return Character.digit(SAME_FIRST_CODE.charAt(call), 10);
                }
                return (thread.get() * 3 + call * 7 + 1) % 10;
            }
        };
    }
}
