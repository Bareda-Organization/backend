package src.backend.academy.dto;

/**
 * 학원 등록 응답의 경고 코드(API_SPEC §6.2 {@code warnings[]}) — 저장을 <b>막지 않고</b> 알리기만 한다.
 *
 * <p>에러 코드({@code ErrorCode})와 타입을 나눈 이유는 응답 위치와 의미가 다르기 때문이다 — 에러는
 * 요청이 처리되지 않았음을 뜻하고 이쪽은 {@code 201} 로 저장된 뒤에 실린다.
 */
public enum AcademyWarning {

    /** 학원명 + 지역이 같은 학원이 이미 있다 — 분원 존재 가능성이 있어 저장은 허용한다. */
    DUPLICATE_NAME_REGION
}
