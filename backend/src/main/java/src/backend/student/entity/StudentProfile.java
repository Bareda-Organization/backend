package src.backend.student.entity;

import java.time.LocalDate;

/**
 * 관계자가 입력하는 학생 정보 묶음(API_SPEC §5.11 POST·PATCH) — 등록과 수정이 같은 값 집합을 쓴다.
 *
 * <p><b>승하차 주소가 여기 없는 것이 사양이다</b>(A-10, 2026-08-24 확정) — 주소는 학부모가 요일별로
 * 등록한다({@code §3.7}). 담을 자리를 만들지 않는 것이 관계자 경로로 그 값이 들어올 길을 없애는
 * 방식이다. 보호자 연락처({@code guardian.phone})는 대상 밖이 아니라 별도 경로({@code
 * PATCH .../guardians[]})로 고친다(Ruling 326) — 등록·수정 값 묶음인 이 타입에는 없다.
 *
 * <p>등록에서 {@code null} 은 "값 없음" 이고, 수정({@link Student#update})에서도 호출부가 유지·지움을 이미 풀어 넘기므로
 * 그대로 저장할 값이다(Ruling 390 — 키 없음과 명시적 {@code null} 은 요청 레코드가 가르고
 * {@link src.backend.global.request.Patch} 가 푼다).
 *
 * @param canGoAlone 혼자 귀가 가능 여부(STU-08). {@code boolean} 이 아니라 {@link Boolean} 인 것은
 *                   수정 요청에서 "언급하지 않음" 을 {@code false} 와 갈라야 하기 때문이다 —
 *                   {@code boolean} 으로 받으면 언급하지 않은 요청이 매번 {@code false} 로 덮어쓴다
 */
public record StudentProfile(String name, String studentPhone, String photoUrl, Gender gender,
        LocalDate birthDate, String grade, String className, String note,
        Boolean canGoAlone) {
}
