package src.backend.audit.repository;

import java.time.LocalDate;

/** 로그인 이력의 날짜·행위별 건수 한 행 — {@link AuditLogRepository#countLoginActionsByDay} 가 돌려준다(§6.18). {@code action} 은 {@code login_success} 등 저장 문자열이다. */
public interface LoginActionCount {

    LocalDate getDay();

    String getAction();

    long getTotal();
}
