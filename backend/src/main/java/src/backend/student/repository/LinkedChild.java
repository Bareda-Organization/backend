package src.backend.student.repository;

import java.time.OffsetDateTime;

/**
 * 자녀 목록 1행 — {@code guardian_student} 와 {@code student} 를 조인해 모아 오는 조회 전용 투영이다
 * (ATT-03 · API_SPEC §3.1).
 *
 * <p>{@code Student} 엔티티를 그대로 꺼내지 않는 이유는 그 안에 사진·특이사항·연락처가 함께 실려
 * 오기 때문이다 — 학부모 앱 응답에 부재해야 하는 값들이라(§1.12), 조립 단계에서 빼는 것보다
 * <b>애초에 꺼내지 않는</b> 편이 필드가 하나 늘 때 자동으로 새는 것을 막는다.
 */
public interface LinkedChild {

    Long getStudentId();

    String getName();

    String getClassName();

    OffsetDateTime getLinkedAt();
}
