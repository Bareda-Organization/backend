package src.backend.student.dto;

import java.time.OffsetDateTime;

import src.backend.student.entity.Student;

/**
 * 퇴원 처리 결과(STU-04, API_SPEC §5.11 DELETE).
 *
 * <p>{@code 204 No Content} 가 아니라 본문을 돌려주는 이유는 §1.9 가 "변경 후 자원 상태를 그대로 반환"
 * 을 요구하기 때문이다 — 퇴원의 변경분은 {@code deleted_at} 하나이고, 그 값이 응답에 없으면
 * 클라이언트가 <b>지워졌는지 지워진 척했는지</b> 구별할 수단이 부재하다.
 *
 * <p>학생 정보 전체를 다시 싣지 않는다 — 퇴원 응답은 명단에서 빠진 학생을 화면에 그리는 자리가
 * 아니고, 여기 얹으면 목록에서 뺀 개인정보가 삭제 응답으로 다시 나간다(§1.12).
 */
public record StudentWithdrawalResponse(Long studentId, OffsetDateTime deletedAt) {

    public static StudentWithdrawalResponse from(Student student) {
        return new StudentWithdrawalResponse(student.getId(), student.getDeletedAt());
    }
}
