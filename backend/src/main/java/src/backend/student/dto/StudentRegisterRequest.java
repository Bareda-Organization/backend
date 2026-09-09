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
 * <p><b>사진을 받는 필드가 여기 부재한 것도 사양이다</b>(Ruling 160) — 요청의 {@code photo} 는
 * 멀티파트의 <b>파일 파트</b>이고(§1.1), 응답·컬럼의 {@code photo_url} 은 서버가 저장한 뒤 만들어 준
 * 주소다. 둘은 같은 값이 아니라서 이름을 가른다. 여기 URL 문자열 자리를 두면 클라이언트가 외부 주소를
 * 그대로 보낼 수 있게 되고, 그 경로는 {@code PhotoStorage} 를 우회한다.
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
        String gender,
        LocalDate birthDate,
        @Size(max = 20) String grade,
        @Size(max = 50) String className,
        Integer seatNo,
        String note,
        @NotNull Boolean canGoAlone) {
}
