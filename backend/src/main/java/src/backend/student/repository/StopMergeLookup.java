package src.backend.student.repository;

import java.math.BigDecimal;
import java.util.List;

import src.backend.student.entity.Stop;

/**
 * 근접 병합(STU-05)이 볼 승하차지 후보를 <b>학원을 잠근 뒤에</b> 돌려준다(Ruling 179).
 *
 * <p>잠금과 조회를 두 메서드로 가르지 않은 것이 이 조각(fragment)의 존재 이유다 — 가르면 호출부가
 * 잠금을 빠뜨려도 컴파일되고 평소에는 정상 동작하며, 같은 자리에 승하차지가 둘 생기는 것은 동시
 * 요청에서만 드러난다. 잠금 없는 후보 조회를 밖에 두지 않아 빠뜨릴 대상 자체를 없앤다.
 */
public interface StopMergeLookup {

    /**
     * 학원을 잠그고 나서 좌표 부근의 후보를 읽는다 — 잠금은 부른 트랜잭션이 끝날 때 풀린다.
     *
     * <p>조회 <b>전에</b> 잡는 이유는 임계 구역이 "조회 → 판정 → 생성" 전체를 덮어야 하기 때문이다.
     * 조회 뒤에 잡으면 이미 읽은 결과가 낡은 채로 판정이 끝나 후보 부재로 갈린다.
     */
    List<Stop> lockAcademyAndFindNearby(Long academyId, BigDecimal lat, BigDecimal lng, BigDecimal box);
}
