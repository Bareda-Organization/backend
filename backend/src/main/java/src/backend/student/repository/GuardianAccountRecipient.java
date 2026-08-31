package src.backend.student.repository;

/**
 * 학생 1명의 보호자 계정 수신자 1행 — 알림 발신(Phase 9, RUN-05·06 · API_SPEC §9.7 {@code run_started}
 * · {@code alighting})이 보호자에게 보낼 계정을 {@code guardian_student} → {@code guardian} →
 * {@code account} 조인으로 모아 오는 결과를 담는 조회 전용 투영이다.
 *
 * <p>{@link GuardianPhone} 과 자매 투영이다 — 저쪽은 연락처(A-10 명단), 이쪽은 알림 수신자
 * (계정 식별자·표시 이름)라 컬럼이 다르다.
 */
public interface GuardianAccountRecipient {

    Long getStudentId();

    Long getAccountId();

    String getName();
}
