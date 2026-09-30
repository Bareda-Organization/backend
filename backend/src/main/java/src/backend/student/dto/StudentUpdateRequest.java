package src.backend.student.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 학생 정보 수정 요청(STU-03, API_SPEC §5.11 PATCH) — 보낸 항목만 반영되고 {@code null} 은 그대로 둔다.
 *
 * <p>사양이 "주소는 대상 밖" 이라 적은 것을 <b>필드 부재로</b> 표현한다(A-10) —
 * {@link StudentRegisterRequest} 와 같은 이유다. 보호자 연락처는 대상 밖이 아니라 {@code guardians[]} 로
 * 받는다(Ruling 326).
 *
 * <p>등록 요청과 필드가 같은데도 타입을 나눈 것은 <b>필수 여부가 다르기 때문</b>이다. 등록은
 * {@code name}·{@code can_go_alone} 이 필수고 수정은 전부 선택이라, 한 타입으로 겸하면 필수 검증을
 * 애너테이션이 아니라 서비스 분기로 옮기게 되고 그 분기는 두 경로 중 한쪽에서 조용히 빠질 수 있다.
 *
 * <p>사진은 {@link StudentRegisterRequest} 와 같은 이유로 여기 부재하다(Ruling 160) — 멀티파트의
 * 파일 파트 {@code photo} 로 온다.
 *
 * <p>{@code canGoAlone} 이 {@link Boolean} 인 것은 "언급하지 않음" 과 {@code false} 를 갈라야 하기
 * 때문이다 — {@code boolean} 이면 이름만 고치는 요청이 혼자 귀가 가능 여부를 매번 {@code false} 로
 * 되돌린다(STU-08).
 */
public record StudentUpdateRequest(
        @Size(max = 50) String name,
        @Size(max = 30) String studentPhone,
        String gender,
        LocalDate birthDate,
        @Size(max = 20) String grade,
        @Size(max = 50) String className,
        @Size(max = 200) String note,
        Boolean canGoAlone,
        @Valid List<GuardianPhoneChange> guardians) {

    /**
     * 보호자 연락처 수정 한 건(Ruling 326) — 이 학생과 연결된 보호자만 고칠 수 있다.
     *
     * <p>숫자·하이픈만 받는다 — 기사·동승자가 명단에서 그대로 눌러 전화를 거는 값이다.
     */
    public record GuardianPhoneChange(
            @NotBlank String guardianId,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[0-9][0-9-]{6,28}[0-9]$") String phone) {
    }
}
