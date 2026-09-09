package src.backend.student.dto;

import src.backend.student.entity.Student;

/**
 * 관계자 웹 학생 목록의 항목 하나(API_SPEC §5.11 {@code items[]}).
 *
 * <p>필드 6개가 사양이 정한 전부다 — 학생 상세의 사진·특이사항·생년월일은 여기 싣지 않는다. 목록은
 * 한 번에 100건까지 나가므로, 민감 항목(L3)을 목록에 얹으면 상세를 한 번도 열지 않고도 학원 전체의
 * 개인정보가 한 응답으로 빠져나간다(§1.12).
 *
 * @param busNo    배정 차량 번호. 노선 편성이 Phase 6·7 소유라 이 Phase 에서는 항상 {@code null} 이다 —
 *                 값이 없다고 필드를 지우면 다음 Phase 가 계약을 다시 바꾼다
 * @param stopName 배정 승하차지 이름. {@code busNo} 와 같은 이유로 {@code null} 이다
 *
 * <p><b>{@code studentId} 는 문자열이다</b>(Ruling 171). {@code API_SPEC} 의 {@code *_id} 타입 표기가
 * {@code string} 20건 · {@code integer}/{@code number} 0건이고, {@code §3.1}·{@code §2.10} 이 같은
 * 이름을 {@code string} 으로 명시한다. {@code §5.11} 은 타입을 적지 않아 처음에는 {@code Long} 이었다.
 *
 * <p>근거는 표기 통일이 아니라 <b>정밀도</b>다 — JavaScript 의 {@code number} 는 2^53 을 넘으면
 * 값을 잃는다. {@code bigint} PK 를 숫자로 내보내는 계약은 언젠가 조용히 틀린 식별자를 주고, 그때는
 * 요청이 실패하는 것이 아니라 <b>다른 학생을 가리킨다.</b> 지금 안 아픈 이유는 시드 id 가 한 자리여서일 뿐이다.
 */
public record StudentSummaryResponse(String studentId, String name, String className, String busNo,
        String stopName, String guardianPhone) {

    /**
     * 목록 1행을 만든다 — 보호자 연락처는 학생 레코드가 아니라 <b>밖에서 조회한 값</b>으로 받는다(A-10).
     *
     * <p>연결된 보호자가 없으면 {@code null} 이고 그것이 정상이다({@code AUTH-11} 계정 미연결) —
     * 감추지 않고 그대로 내보내 관계자 화면이 "아직 연결되지 않음" 을 드러내게 한다.
     */
    public static StudentSummaryResponse of(Student student, String guardianPhone) {
        return new StudentSummaryResponse(String.valueOf(student.getId()), student.getName(),
                student.getClassName(), null, null, guardianPhone);
    }
}
