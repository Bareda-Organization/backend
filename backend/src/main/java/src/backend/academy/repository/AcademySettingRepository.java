package src.backend.academy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.academy.entity.AcademySetting;

/**
 * {@link AcademySetting} 영속성 접근(Phase 11, EXC-01).
 *
 * <p>PK 가 곧 {@code academy_id} 라 {@link AcademyRepository} 와 같은 이유로 학원 격리 조건을 따로
 * 붙이지 않는다 — {@code findById(academyId)} 자체가 이미 그 학원으로 좁혀져 있다.
 */
public interface AcademySettingRepository extends JpaRepository<AcademySetting, Long> {
}
