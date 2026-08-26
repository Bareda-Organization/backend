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
 */
public record StudentSummaryResponse(Long studentId, String name, String className, String busNo,
        String stopName, String guardianPhone) {

    /**
     * 목록 1행을 만든다 — 보호자 연락처는 학생 레코드가 아니라 <b>밖에서 조회한 값</b>으로 받는다(A-10).
     *
     * <p>연결된 보호자가 없으면 {@code null} 이고 그것이 정상이다({@code AUTH-11} 계정 미연결) —
     * 감추지 않고 그대로 내보내 관계자 화면이 "아직 연결되지 않음" 을 드러내게 한다.
     */
    public static StudentSummaryResponse of(Student student, String guardianPhone) {
        return new StudentSummaryResponse(student.getId(), student.getName(), student.getClassName(),
                null, null, guardianPhone);
    }
}
