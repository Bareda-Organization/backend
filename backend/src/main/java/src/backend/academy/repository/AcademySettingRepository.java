package src.backend.academy.repository;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.academy.entity.AcademySetting;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link AcademySetting} 영속성 접근(Phase 11, EXC-01).
 *
 * <p>PK 가 곧 {@code academy_id} 라 {@link AcademyRepository} 와 같은 이유로 학원 격리 조건을 따로
 * 붙이지 않는다 — {@code findById(academyId)} 자체가 이미 그 학원으로 좁혀져 있다.
 */
public interface AcademySettingRepository extends JpaRepository<AcademySetting, Long> {

    /**
     * 행이 없으면 기본값으로 자가 치유해 만든다(GET·PATCH 공용, BR-096) — 예전에는 조회·수정 두
     * 서비스가 이 get-or-create 를 각자 복제해 규칙이 한쪽만 바뀔 위험이 있었다({@code CODE_CONVENTIONS
     * §20.4}). 동시에 두 요청이 모두 "행 없음" 을 보고 둘 다 {@code save} 를 시도하면 뒤의 하나는 PK
     * 충돌({@code DataIntegrityViolationException}) 이라 500 대신 그 순간 커밋된 행을 다시 읽는다.
     */
    @AcademyScopeExempt(reason = "PK 가 곧 academy_id 다(위 클래스 자바독) — 안의 findById·save 모두 "
            + "그 academyId 하나로만 좁혀져 있어 별도 학원 조건을 붙일 대상이 없다. 람다가 아니라 "
            + "일반 메서드 본문으로 쓴 것은 AcademyScopeRepositoryConventionTest 가 synthetic lambda 메서드는 "
            + "이 애너테이션의 적용 대상으로 보지 않아서다")
    default AcademySetting findOrCreate(Long academyId) {
        Optional<AcademySetting> existing = findById(academyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return save(AcademySetting.forAcademy(academyId));
        } catch (DataIntegrityViolationException e) {
            Optional<AcademySetting> racedIn = findById(academyId);
            if (racedIn.isEmpty()) {
                throw e;
            }
            return racedIn.get();
        }
    }
}
