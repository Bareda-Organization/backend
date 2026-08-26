package src.backend.student.entity;

import java.time.LocalDate;

/**
 * 관계자가 입력하는 학생 정보 묶음(API_SPEC §5.11 POST·PATCH) — 등록과 수정이 같은 값 집합을 쓴다.
 *
 * <p><b>보호자 연락처와 승하차 주소가 여기 없는 것이 사양이다</b>(A-10, 2026-08-24 확정). 연락처는
 * {@code guardian_student} → {@code guardian} → {@code account.phone} 조회값이고 주소는 학부모가
 * 요일별로 등록한다({@code §3.7}) — 담을 자리를 만들지 않는 것이 관계자 경로로 그 값이 들어올 길을
 * 없애는 방식이다.
 *
 * <p><b>{@code null} 은 "바꾸지 않음" 이다</b> — PATCH 는 보낸 필드만 반영하므로 값을 비우는 것과
 * 언급하지 않는 것을 JSON 만으로 가르려면 별도 표현이 필요하고 이 사양은 그것을 요구하지 않는다
 * ({@code AcademyProfile} 과 같은 규약).
 *
 * @param canGoAlone 혼자 귀가 가능 여부(STU-08). {@code boolean} 이 아니라 {@link Boolean} 인 것은
 *                   수정 요청에서 "언급하지 않음" 을 {@code false} 와 갈라야 하기 때문이다 —
 *                   {@code boolean} 으로 받으면 언급하지 않은 요청이 매번 {@code false} 로 덮어쓴다
 */
public record StudentProfile(String name, String studentPhone, String photoUrl, Gender gender,
        LocalDate birthDate, String grade, String className, Integer seatNo, String note,
        Boolean canGoAlone) {
}
