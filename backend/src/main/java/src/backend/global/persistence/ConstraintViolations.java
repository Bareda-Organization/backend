package src.backend.global.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 제약이 그 이름인지만 본다(BR-099,
 * 판별 10벌 통합). 다른 제약까지 같은 {@code 409} 로 옮기면 원인이 감춰지므로 이름을 정확히 대조한다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve) {
                return constraintName.equals(cve.getConstraintName());
            }
        }
        return false;
    }
}
