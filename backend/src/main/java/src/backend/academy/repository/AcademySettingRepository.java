package src.backend.academy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 서비스가 이 get-or-create 를 각자 복제해 규칙이 한쪽만 바뀔 위험이 있었다
     * ({@code CODE_CONVENTIONS §20.4}).
     *
     * <p>동시에 두 요청이 모두 "행 없음" 을 보는 경우를 <b>DB 가 흡수</b>한다(BR-246) — {@code INSERT ... ON CONFLICT
     * DO NOTHING} 이라 뒤 요청은 앞 요청의 커밋을 기다린 뒤 아무것도 하지 않고 그 행을 읽는다. {@code save()} 와
     * {@code catch} 로는 막을 수 없다: PK 를 직접 지정한 엔티티의 {@code save} 는 {@code merge} 라 INSERT 가 커밋
     * 시점까지 미뤄져 {@code catch} 가 발동하지 않고, 설령 flush 로 앞당겨도 PostgreSQL 은 위반이 난 트랜잭션을 더
     * 쓰지 못하게 해 다시 읽기가 실패한다.
     */
    @AcademyScopeExempt(reason = "PK 가 곧 academy_id 다(위 클래스 자바독) — 안의 findById·insertIfAbsent 모두 "
            + "그 academyId 하나로만 좁혀져 있어 별도 학원 조건을 붙일 대상이 없다. 람다가 아니라 "
            + "일반 메서드 본문으로 쓴 것은 AcademyScopeRepositoryConventionTest 가 synthetic lambda 메서드는 "
            + "이 애너테이션의 적용 대상으로 보지 않아서다")
    default AcademySetting findOrCreate(Long academyId) {
        Optional<AcademySetting> existing = findById(academyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        insertIfAbsent(academyId, AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES);
        Optional<AcademySetting> created = findById(academyId);
        if (created.isEmpty()) {
            throw new IllegalStateException("academy_setting 행을 만들고도 읽지 못했다 — 학원 행이 없다: academyId=" + academyId);
        }
        return created.get();
    }

    /** 행이 이미 있으면 아무것도 하지 않는 INSERT — 갱신 행 수 0 이면 다른 요청이 먼저 만들었다. */
    @AcademyScopeExempt(reason = "PK 가 곧 academy_id 다 — findOrCreate 의 내부 단계이고 그 academyId 하나로만 좁혀져 있다")
    @Modifying
    @Query(value = "INSERT INTO academy_setting (academy_id, no_show_wait_minutes) VALUES (:academyId, :minutes) "
            + "ON CONFLICT (academy_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("academyId") Long academyId, @Param("minutes") int minutes);
}
