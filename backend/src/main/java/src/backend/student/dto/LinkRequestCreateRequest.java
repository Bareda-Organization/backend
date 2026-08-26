package src.backend.student.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 자녀 연결 요청(P-02, API_SPEC §3.2 요청).
 *
 * <p>학생을 <b>로그인 아이디로</b> 지목한다. 학생 식별자(PK)로 받지 않는 것은 학부모가 그 값을 알
 * 방법이 부재하고, 알 수 있게 되면 번호를 훑어 남의 자녀에게 연결을 신청할 수 있기 때문이다.
 *
 * <p>보호자를 지정하는 자리가 부재한 것도 사양이다 — 요청 주체는 토큰이 정한다(§1.5).
 */
public record LinkRequestCreateRequest(@NotBlank String studentLoginId) {
}
