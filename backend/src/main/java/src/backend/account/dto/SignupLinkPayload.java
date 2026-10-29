package src.backend.account.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * 가입 수락 시 계정에 이을 실제 레코드(AUTH-11 · API_SPEC §5.2 {@code link}).
 *
 * <p>역할마다 채우는 자리가 다르다 — {@code parent}·{@code student} 는 {@code student_ids[]},
 * {@code driver}·{@code escort} 는 {@code manager_id} 다. 둘을 한 레코드에 둔 이유는 요청 본문의
 * {@code link} 가 하나뿐이기 때문이고, <b>어느 자리가 필수인가는 승인 대상 계정의 역할이 정한다</b> —
 * 이 레코드는 그 판정을 하지 않는다({@code SignupAccountLinker}).
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupLinkPayload(@Size(max = SignupLinkPayload.MAX_STUDENT_IDS) List<Long> studentIds, Long managerId) {

    /** 한 번에 이을 수 있는 학생 수 상한(API_SPEC §5.2) — 수락 트랜잭션의 행 잠금이 열린 채 도는 조회·쓰기의 길이를 막는다. */
    public static final int MAX_STUDENT_IDS = 20;
}
