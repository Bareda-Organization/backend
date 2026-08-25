package src.backend.academy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;

/** {@link Academy} 영속성 접근. */
public interface AcademyRepository extends JpaRepository<Academy, Long> {

    /**
     * 가입용 학원 검색(API_SPEC §2.1) — 이름·코드 양쪽 부분일치, 지정한 상태만.
     *
     * <p>비활성 학원 제외 조건을 이 쿼리에 직접 넣는다 — 학원 격리 강제 장치 자체는 Task 5 소관이지만,
     * "지금 필요한 격리 조건은 쿼리에 넣는다"(Phase 2 컨벤션 §6)는 이 태스크가 진다.
     */
    @Query("SELECT a FROM Academy a WHERE a.status = :status "
            + "AND (a.name LIKE CONCAT('%', :q, '%') OR a.code LIKE CONCAT('%', :q, '%'))")
    List<Academy> searchByStatus(@Param("status") AcademyStatus status, @Param("q") String q);
}
