package src.backend.global.retention;

import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보존 정리 · 익명화가 한 묶음을 도는 트랜잭션 — 일반 연결의 {@code statement_timeout} 30초를 <b>이 트랜잭션 안에서만</b> 푼다
 * ({@code SET LOCAL}, DA-4 · Ruling 899 L). 트랜잭션이 끝나면 연결은 풀에 30초 상한 그대로 돌아간다.
 */
@Component
public class RetentionTransactions {

    private final TransactionTemplate transaction;

    private final JdbcTemplate jdbc;

    public RetentionTransactions(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    /** {@code work} 를 문장 시간 상한이 풀린 새 트랜잭션 하나로 돌리고 결과를 돌려준다. */
    public <T> T call(Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = 0");
            return work.get();
        });
    }
}
