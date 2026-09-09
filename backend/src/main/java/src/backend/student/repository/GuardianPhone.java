package src.backend.student.repository;

/**
 * 학생 1명의 보호자 연락처 1행 — 학생 목록이 {@code guardian_student} → {@code guardian} →
 * {@code account.phone} 을 조인해 모아 오는 결과를 담는 조회 전용 투영이다(A-10).
 *
 * <p>연락처를 {@code student} 에 복제하지 않기로 한 결정의 대가가 이 조인이다. 학생마다 질의를
 * 하나씩 붙이면 한 페이지(최대 100건)가 질의 100건이 되므로 <b>한 페이지분을 한 번에</b> 모으고,
 * 그러려면 결과가 두 컬럼이라 담을 타입이 필요하다.
 */
public interface GuardianPhone {

    Long getStudentId();

    String getPhone();
}
