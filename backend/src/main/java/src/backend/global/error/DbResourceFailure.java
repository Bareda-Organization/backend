package src.backend.global.error;

import java.sql.SQLException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 어느 DB 예외가 "자원 상태" 라 {@code 503 SERVER_BUSY} 인지 가르는 단 한 곳(R46 S-7) — {@link GlobalExceptionHandler} 와
 * 인증 필터({@code JwtAuthenticationFilter}, 컨트롤러 어드바이스 바깥)가 같은 목록을 쓴다. 연결 풀 고갈
 * ({@link CannotCreateTransactionException}) · 연결 끊김({@link DataAccessResourceFailureException}) · 잠금 대기 초과
 * ({@link PessimisticLockingFailureException}) · 쿼리 취소({@link QueryTimeoutException}). 그 밖의 DB 예외(SQL 문법 오류 등)는
 * 코드 결함이라 {@code 500} 이다.
 */
public final class DbResourceFailure {

    private DbResourceFailure() {
    }

    /**
     * 자원 오류인가 — 단 잠금 예외 중 <b>교착 · 직렬화 실패</b>는 자원 상태가 아니라 잠금 순서가 어긋난 코드 결함이라 뺀다(BR-352).
     * 실제 PostgreSQL 에서는 교착도 잠금 대기 초과와 같은 {@code CannotAcquireLockException} 으로 나와 예외 클래스로는 가를 수 없고
     * SQLState 로 가른다.
     */
    public static boolean isResourceUnavailable(Throwable e) {
        boolean resourceType = e instanceof CannotCreateTransactionException
                || e instanceof DataAccessResourceFailureException
                || e instanceof PessimisticLockingFailureException
                || e instanceof QueryTimeoutException;
        return resourceType && !isDeadlockOrSerializationFailure(e);
    }

    /**
     * PostgreSQL 교착({@code 40P01}) · 직렬화 실패({@code 40001}) — 원인 사슬에 그 SQLState 가 있다. Spring 7 이 두 전용 예외
     * ({@code DeadlockLoserDataAccessException} · {@code CannotSerializeTransactionException})를 폐기 예정으로 돌려 클래스로 가르지 않는다.
     */
    private static boolean isDeadlockOrSerializationFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
