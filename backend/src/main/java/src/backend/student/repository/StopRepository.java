package src.backend.student.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Stop;

/**
 * {@link Stop} 영속성 접근.
 *
 * <p>근접 병합 후보 조회는 {@link StopMergeLookup} 이 갖는다 — 잠금 없는 후보 조회를 여기 두면
 * 호출부가 잠금을 빠뜨려도 컴파일되고, 그때 같은 자리에 승하차지가 둘 생긴다(Ruling 179).
 */
public interface StopRepository extends JpaRepository<Stop, Long>, StopMergeLookup {

    /**
     * 노선 계산 ①단계가 쓸 승하차지 좌표를 한 번에 읽는다 — 학원 밖 승하차지는 <b>결과에서 빠진다.</b>
     *
     * <p>빠뜨리는 것이 이 조회의 일이다. 다른 학원의 승하차지 번호가 어떤 경로로 명단에 섞여
     * 들어오더라도 좌표를 얻지 못한 것으로 다뤄져 그 학생이 분리될 뿐, 남의 학원 주소가 이 학원
     * 버스의 정차지가 되지는 않는다(횡단 규칙 7).
     */
    List<Stop> findAllByIdInAndAcademyId(Collection<Long> ids, Long academyId);
}
