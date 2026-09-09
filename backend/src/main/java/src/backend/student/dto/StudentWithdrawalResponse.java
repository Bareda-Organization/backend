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
 *
 * <p><b>{@code studentId} 는 문자열이다</b>(Ruling 171). {@code API_SPEC} 의 {@code *_id} 타입 표기가
 * {@code string} 20건 · {@code integer}/{@code number} 0건이고, {@code §3.1}·{@code §2.10} 이 같은
 * 이름을 {@code string} 으로 명시한다. {@code §5.11} 은 타입을 적지 않아 처음에는 {@code Long} 이었다.
 *
 * <p>근거는 표기 통일이 아니라 <b>정밀도</b>다 — JavaScript 의 {@code number} 는 2^53 을 넘으면
 * 값을 잃는다. {@code bigint} PK 를 숫자로 내보내는 계약은 언젠가 조용히 틀린 식별자를 주고, 그때는
 * 요청이 실패하는 것이 아니라 <b>다른 학생을 가리킨다.</b> 지금 안 아픈 이유는 시드 id 가 한 자리여서일 뿐이다.
 */
public record StudentWithdrawalResponse(String studentId, OffsetDateTime deletedAt) {

    public static StudentWithdrawalResponse from(Student student) {
        return new StudentWithdrawalResponse(String.valueOf(student.getId()), student.getDeletedAt());
    }
}
