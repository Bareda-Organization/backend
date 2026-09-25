package src.backend.notification.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;

/**
 * BR-096(2차 정리 N) — {@code NotificationSettingQueryService.get} 이 GET 인데 행이 없으면
 * {@code save} 까지 한다(get-or-create). 같은 계정이 설정 행 없이 두 기기에서 동시에 조회하면
 * 두 트랜잭션 모두 {@code findById} 를 비어 있는 채로 보고 각자 {@code save} 를 시도해, 뒤 커밋이
 * PK({@code account_id}) UNIQUE 위반 {@code DataIntegrityViolationException} 으로 500 이 된다.
 */
@SpringBootTest
class NotificationSettingQueryConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private NotificationSettingQueryService notificationSettingQueryService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 설정 행이 아직 없는 계정을 두 스레드가 동시에 조회하면 — 조회가 쓰기를 겸하지 않아야
     * 한다(§7 CQRS) — 둘 다 예외 없이 기본값을 돌려주고, 조회만으로는 행이 생기지 않는다.
     */
    @Test
    void 설정_행이_없는_계정을_두_스레드가_동시에_조회해도_500이_나지_않는다() throws Exception {
        long accountId = 새_학부모_계정();
        AuthUser requester = new AuthUser(accountId, 1L, Role.PARENT, AccountStatus.ACTIVE);
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> 첫째 = pool.submit(조회_호출(requester, 출발));
            Future<Throwable> 둘째 = pool.submit(조회_호출(requester, 출발));
            출발.countDown();

            List<Throwable> 결과 = java.util.Arrays.asList(첫째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    둘째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertThat(결과)
                    .as("조회는 500 을 내면 안 된다 — 실제 결과=%s", 결과)
                    .allSatisfy(t -> assertThat(t).isNull());
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Throwable> 조회_호출(AuthUser requester, CountDownLatch 출발) {
        return () -> {
            출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                notificationSettingQueryService.get(requester);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private long 새_학부모_계정() {
        String loginId = "p12t7conc" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        long accountId = accountRepository.save(Account.forSignup(1L, loginId, "{noop}password", "학부모",
                "010-7000-0009", null, Role.PARENT)).getId();
        jdbcTemplate.update("DELETE FROM notification_setting WHERE account_id = ?", accountId);
        return accountId;
    }
}
