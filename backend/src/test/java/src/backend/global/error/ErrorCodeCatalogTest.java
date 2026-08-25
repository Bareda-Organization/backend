package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Phase 3 가 쓰는 에러 코드의 <b>HTTP 상태</b>를 API_SPEC §8 에러 사전과 대조한다.
 *
 * <p>{@code ErrorCode} 를 참조해 기대값을 만들지 않고 <b>사양에서 손으로 옮긴 리터럴</b>을 쓴다 —
 * 상수에서 유도하면 상수가 잘못 채워져도 대조 대상이 같은 값을 베껴 항상 일치한다
 * ({@code EXPECTED_PUBLIC_ENDPOINTS} 와 같은 이유의 독립 축이다, Ruling 103).
 *
 * <p>이 중 8종은 T1 이 <b>소비자보다 먼저</b> 더한 것들이다. T2·T3 이 각자 이 파일에 상수를 더하면
 * 병합 충돌이 확정이라 선행 태스크가 한 번에 더했고, 그래서 <b>T1 시점에는 이 코드들을 던지는 자리가
 * 부재</b>했다. 소비자가 없는 동안 상태 코드가 틀린 채로 남아 있으면 T2·T3 이 그것을 사실로 삼는다.
 *
 * <p>9번째 {@code SIGNUP_TARGET_BLOCKED} 는 T2 의 {@code AUTH_ACCOUNT_BLOCKED} 재사용을 걷어내며
 * 더한 것이다(Ruling 147). 여기 실은 이유는 <b>403 이 아니라 409</b> 라는 판정이 이 표에서만 고정되기
 * 때문이다 — 요청 주체는 인가돼 있고 막는 것은 대상 자원의 상태라 {@code APPROVAL_ALREADY_DECIDED}
 * 와 같은 형태다. 상수 선언만 두면 다음 사람이 "차단이니 403" 으로 되돌려도 아무것도 실패하지 않는다.
 */
class ErrorCodeCatalogTest {

    /** API_SPEC §8.1·§8.3·§8.5 의 "코드 | HTTP" 열을 그대로 옮긴 것. */
    private static final Map<ErrorCode, HttpStatus> SPEC_STATUS = new LinkedHashMap<>(Map.of(
            ErrorCode.STAFF_QUOTA_EXCEEDED, HttpStatus.CONFLICT,
            ErrorCode.APPROVAL_ALREADY_DECIDED, HttpStatus.CONFLICT,
            ErrorCode.SIGNUP_REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCode.LINK_REQUIRED, HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.STUDENT_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCode.MANAGER_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCode.ALREADY_LINKED, HttpStatus.CONFLICT,
            ErrorCode.ACCOUNT_NOT_BLOCKED, HttpStatus.CONFLICT,
            ErrorCode.SIGNUP_TARGET_BLOCKED, HttpStatus.CONFLICT));

    @Test
    void Phase_3_이_쓰는_에러_코드_9종의_HTTP_상태가_사양과_같다() {
        assertThat(SPEC_STATUS).hasSize(9);
        assertThat(SPEC_STATUS).allSatisfy((code, expected) ->
                assertThat(code.getStatus())
                        .as("%s 의 HTTP 상태가 API_SPEC §8 과 어긋난다", code.name())
                        .isEqualTo(expected));
    }

    /**
     * {@code ACADEMY_NOT_FOUND} 는 <b>미등록과 비활성을 함께</b> 가리키는 404 다(§8.5).
     *
     * <p>이 사실이 Phase 3 목표 4의 세 번째 문장("비활성 학원을 지정한 회원가입은 거부된다")의 기대
     * 응답을 막연한 4xx 가 아니라 {@code 404 ACADEMY_NOT_FOUND} 로 못 박는다.
     */
    @Test
    void ACADEMY_NOT_FOUND_는_404_다() {
        assertThat(ErrorCode.ACADEMY_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
