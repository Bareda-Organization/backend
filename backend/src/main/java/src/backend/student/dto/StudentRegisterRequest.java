package src.backend.student.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 학생 등록 요청(STU-02, API_SPEC §5.11 POST).
 *
 * <p><b>보호자 연락처와 승하차 주소를 받는 필드가 부재한 것이 사양이다</b>(A-10, 2026-08-24 확정) —
 * 연락처는 연결된 보호자 계정에서 조회하고 주소는 학부모가 요일별로 등록한다(§3.7). 본문에 실어
 * 보내도 바인딩될 자리가 없어 무시된다({@code AcademyRegisterRequest} 의 {@code code} 와 같은 방식).
 *
 * <p>{@code photoUrl} 은 사양 표의 {@code photo}({@code file · string})에 대응한다 — 이 저장소에 파일
 * 업로드·객체 저장 인프라가 부재해 <b>URL 문자열만 받기로 확정</b>했고(Ruling 156), 이름도 저장
 * 컬럼({@code student.photo_url})과 응답 필드에 맞췄다. 멀티파트 업로드는 저장 위치 결정을 동반하는
 * 별도 단위다.
 *
 * <p>길이 상한은 {@code student} 테이블 컬럼 정의를 그대로 옮겼다. 상한을 코드에 두지 않으면 초과
 * 입력이 {@code 422} 가 아니라 DB 예외를 거친 {@code 500} 으로 나간다.
 *
 * <p>{@code gender} 를 enum 이 아니라 문자열로 받는다 — enum 바인딩 실패는 {@code 400} 이나
 * {@code 500} 으로 새기 쉬워, 사양이 정한 {@code 422 VALIDATION_FAILED} 로 옮기는 판정을 서비스가 한다.
 */
public record StudentRegisterRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 30) String studentPhone,
        @Size(max = 255) String photoUrl,
        String gender,
        LocalDate birthDate,
        @Size(max = 20) String grade,
        @Size(max = 50) String className,
        Integer seatNo,
        String note,
        @NotNull Boolean canGoAlone) {
}
