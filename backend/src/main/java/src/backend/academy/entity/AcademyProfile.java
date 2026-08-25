package src.backend.academy.entity;

/**
 * 학원의 수정 가능한 정보 묶음(API_SPEC §6.3 PATCH) — {@code code} 와 {@code status} 는 여기 없다.
 *
 * <p>{@code code} 는 서버 생성값이라 수정 경로 자체가 부재하고, {@code status} 는 값 수정이 아니라
 * 상태 전이(ACAD-04 비활성화)라 별도 메서드({@link Academy#changeStatus})가 받는다 — 한 묶음에 넣으면
 * "이름을 고치는 일" 과 "학원을 정지시키는 일" 이 같은 호출로 보인다.
 *
 * <p><b>{@code null} 은 "바꾸지 않음" 이다</b> — PATCH 는 보낸 필드만 반영하므로, 값을 비우는 것과
 * 언급하지 않는 것을 JSON 만으로 가르려면 별도 표현이 필요하고 이 사양은 그것을 요구하지 않는다.
 */
public record AcademyProfile(String name, String region, String address, String contact, String memo) {
}
