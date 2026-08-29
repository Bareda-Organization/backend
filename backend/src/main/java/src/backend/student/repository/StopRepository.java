package src.backend.student.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Stop;

/**
 * {@link Stop} 영속성 접근.
 *
 * <p>근접 병합 후보 조회는 {@link StopMergeLookup} 이 갖는다 — 잠금 없는 후보 조회를 여기 두면
 * 호출부가 잠금을 빠뜨려도 컴파일되고, 그때 같은 자리에 승하차지가 둘 생긴다(Ruling 179).
 */
public interface StopRepository extends JpaRepository<Stop, Long>, StopMergeLookup {
}
