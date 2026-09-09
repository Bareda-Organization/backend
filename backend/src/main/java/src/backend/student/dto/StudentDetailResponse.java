package src.backend.student.dto;

import java.time.LocalDate;
import java.util.Locale;

import src.backend.student.entity.Student;

/**
 * 관계자 웹 학생 상세(API_SPEC §5.11 {@code GET · POST · PATCH /staff/students}).
 *
 * <p>쓰기 응답도 이 타입이다 — §1.9 가 "변경 후 자원 상태를 그대로 반환" 을 요구하므로, 등록·수정이
 * 별도 응답을 조립하면 같은 자원의 표현이 세 벌로 갈린다.
 *
 * <p>필드가 12개라 {@code p2-controller-conventions §3} 의 "10개를 넘으면 두 가지 일을 하는지 먼저
 * 보라" 에 걸린다. 이 응답이 하는 일은 <b>학생 레코드 1건의 표현</b> 하나이고, 12개 중 10개가
 * {@code student} 테이블 컬럼 그대로다 — 나눌 축이 있다면 사양이 화면을 나눴어야 한다.
 *
 * <p>이것은 <b>관계자 · 메인 관리자 전용 표현</b>이다(§1.12) — 학부모·학생 앱은 {@code photo_url} 과
 * 연락처 원문을 보지 못하므로 이 DTO 를 재사용하지 않는다.
 *
 * @param gender        {@code male} · {@code female} 소문자 문자열(§9.8) — {@code Gender} 를 그대로
 *                      직렬화하면 상수 이름(대문자)이 나가 계약과 어긋난다
 * @param guardianPhone 연결된 보호자 계정의 연락처. 학생 레코드에 복제하지 않는 조회값이라
 *                      <b>밖에서 받아</b> 조립하며, 연결이 없으면 {@code null} 이다(A-10)
 *
 * <p><b>{@code studentId} 는 문자열이다</b>(Ruling 171). {@code API_SPEC} 의 {@code *_id} 타입 표기가
 * {@code string} 20건 · {@code integer}/{@code number} 0건이고, {@code §3.1}·{@code §2.10} 이 같은
 * 이름을 {@code string} 으로 명시한다. {@code §5.11} 은 타입을 적지 않아 처음에는 {@code Long} 이었다.
 *
 * <p>근거는 표기 통일이 아니라 <b>정밀도</b>다 — JavaScript 의 {@code number} 는 2^53 을 넘으면
 * 값을 잃는다. {@code bigint} PK 를 숫자로 내보내는 계약은 언젠가 조용히 틀린 식별자를 주고, 그때는
 * 요청이 실패하는 것이 아니라 <b>다른 학생을 가리킨다.</b> 지금 안 아픈 이유는 시드 id 가 한 자리여서일 뿐이다.
 */
public record StudentDetailResponse(String studentId, String name, String studentPhone, String photoUrl,
        String gender, LocalDate birthDate, String grade, String className, Integer seatNo, String note,
        boolean canGoAlone, String guardianPhone) {

    public static StudentDetailResponse of(Student student, String guardianPhone) {
        return new StudentDetailResponse(String.valueOf(student.getId()), student.getName(),
                student.getStudentPhone(),
                student.getPhotoUrl(), lowerCase(student), student.getBirthDate(), student.getGrade(),
                student.getClassName(), student.getSeatNo(), student.getNote(), student.isCanGoAlone(),
                guardianPhone);
    }

    /** 미입력 성별은 {@code null} 로 남긴다 — 빈 문자열로 바꾸면 "모름" 과 "값이 있음" 이 섞인다. */
    private static String lowerCase(Student student) {
        return student.getGender() == null ? null : student.getGender().name().toLowerCase(Locale.ROOT);
    }
}
