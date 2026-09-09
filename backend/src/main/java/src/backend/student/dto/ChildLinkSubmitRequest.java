package src.backend.student.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 학부모가 입력한 인증 코드(P-02, API_SPEC §3.4 요청).
 *
 * <p>연결 요청 식별자를 함께 받지 않는다 — 받으면 어느 요청을 소비할지 클라이언트가 정하게 되어,
 * 코드와 요청이 어긋난 조합을 서버가 다시 대조해야 한다. 코드 하나로 발급분을 찾는 편이 대조 지점을
 * 하나로 유지한다.
 */
public record ChildLinkSubmitRequest(@NotBlank String code) {
}
