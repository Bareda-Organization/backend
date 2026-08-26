package src.backend.student.dto;

import src.backend.student.entity.Student;

/**
 * 연결 완료 결과(P-02, API_SPEC §3.4 응답 {@code 201}).
 *
 * <p>필드 2개가 사양이 정한 전부다 — 학부모가 "누구와 연결됐는지" 를 화면에 확인하는 자리이고,
 * 자녀의 나머지 정보는 자녀 목록(§3.1)이 돌려준다. 여기 더 실으면 연결 응답이 개인정보 조회 경로를
 * 하나 더 여는 셈이 된다(§1.12).
 *
 * <p>{@code student_id} 는 문자열이다 — §3.4 는 타입을 적지 않으나 같은 이름을 명시한 §3.1 이
 * {@code string} 이고, 한 클라이언트가 두 응답에서 받은 값을 견주는 자리라 형태를 가르지 않는다.
 */
public record ChildLinkedResponse(String studentId, String name) {

    public static ChildLinkedResponse from(Student student) {
        return new ChildLinkedResponse(String.valueOf(student.getId()), student.getName());
    }
}
