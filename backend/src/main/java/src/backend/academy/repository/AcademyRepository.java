package src.backend.academy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;

/**
 * {@link Academy} 영속성 접근.
 *
 * <p>이 저장소에는 학원 격리 조건이 붙지 않는다 — {@code academy} 는 테넌트 루트 자신이라
 * 자기 자신으로 좁힌다는 말이 성립하지 않고, ERD §6.1 의 직접 보유·부모 경유 두 분류 어디에도
 * 속하지 않는다. {@code AcademyScopeRepositoryConventionTest} 의 검사 대상에서도 같은 근거로 빠진다.
 */
public interface AcademyRepository extends JpaRepository<Academy, Long> {

    /** 상태별 학원 수(API_SPEC §6.1 {@code summary}) — 목록 필터·쪽과 무관한 전체 값이라 조건 없이 센다. */
    long countByStatus(AcademyStatus status);

    /** 학원 코드 중복 확인 — 자동 생성기가 충돌을 흡수하려면 후보값의 사용 여부를 먼저 알아야 한다(ACAD-02). */
    boolean existsByCode(String code);

    /**
     * 학원명 + 지역이 같은 학원이 이미 있는지 본다(API_SPEC §6.2).
     *
     * <p>등록을 막는 조회가 아니라 <b>경고를 붙이기 위한</b> 조회다 — 분원이 있을 수 있어 저장은 허용하고
     * {@code warnings[]} 에 {@code DUPLICATE_NAME_REGION} 을 담는다.
     */
    boolean existsByNameAndRegion(String name, String region);

    /**
     * 학원 행을 잠그고 그 학원 안의 작업을 직렬화한다(BR-201) — 같은 학원의 회차 여러 개가 병렬 확정 스레드에서 동승자를
     * 동시에 고르면 서로의 미커밋 배정을 못 봐 같은 동승자가 겹치는 회차 여러 개에 배정된다. 잠금은 트랜잭션이 끝날 때
     * 풀리므로 뒤 스레드는 앞 스레드가 커밋한 배정을 읽는다. 다른 학원의 확정은 막지 않는다.
     *
     * <p>{@code FOR NO KEY UPDATE} 인 이유 — 학생·버스 등 학원을 참조하는 행의 INSERT 가 잡는 {@code FOR KEY SHARE}
     * 와 충돌하지 않아, 그 등록들을 기다리게 하지도 서로 잠금을 올리다 교착하지도 않는다.
     */
    @Query(value = "SELECT id FROM academy WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    /**
     * 메인 관리자 콘솔의 학원 목록·검색(API_SPEC §6.1).
     *
     * <p>{@code status} 필터를 nullable 파라미터가 아니라 <b>상태 집합</b>으로 받는다 — 필터를 걸지
     * 않으면 호출부가 전체 상태를 넘긴다. {@code :status IS NULL OR ...} 형태는 JPQL 파라미터의 타입을
     * 추론할 근거가 사라져 방언에 따라 동작이 갈리고, 무엇보다 "필터 없음" 이 조건절 안에 숨는다.
     *
     * <p>{@code q} 는 호출부가 {@link src.backend.global.persistence.LikeEscape} 로 이스케이프해 넘기고
     * 이 쿼리는 {@code ESCAPE '\'} 로 그것을 해석만 한다. 검색어가 비면 빈 문자열을 넘겨 전건이 걸린다.
     *
     * <p>정렬은 {@code Pageable} 이 실어 오므로 JPQL 에 {@code ORDER BY} 를 두지 않는다 — 두면
     * {@code Pageable} 의 정렬과 어느 쪽이 이기는지가 호출부에서 보이지 않는다.
     */
    @Query("SELECT a FROM Academy a WHERE a.status IN :statuses "
            + "AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' "
            + "OR LOWER(a.code) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')")
    Page<Academy> searchForConsole(@Param("q") String q,
            @Param("statuses") Collection<AcademyStatus> statuses, Pageable pageable);

    /**
     * 가입용 학원 검색(API_SPEC §2.1) — 이름·코드 양쪽 부분일치, 지정한 상태만.
     *
     * <p>비활성 학원 제외 조건을 이 쿼리에 직접 넣는다 — 학원 격리 강제 장치 자체는 Task 5 소관이지만,
     * "지금 필요한 격리 조건은 쿼리에 넣는다"(Phase 2 컨벤션 §6)는 이 태스크가 진다.
     *
     * <p>{@code q} 는 호출부({@code AcademySearchQueryService})가 {@code %}·{@code _} 를 이스케이프해
     * 넘긴다고 가정한다 — 이 쿼리는 {@code ESCAPE '\'} 로 그 이스케이프를 해석만 한다. {@code Pageable}
     * 은 결과 상한을 걸기 위함이다(비인증 공개 엔드포인트라 {@code q="%"} 같은 값이 전체 학원을
     * 반환하는 것을 막는다).
     *
     * <p>{@code ORDER BY} 없는 {@code LIMIT} 은 행 순서가 정해지지 않는다 — 같은 조건으로 다시 호출해도
     * 상한에 걸려 잘려나가는 쪽이 매번 달라질 수 있어, 사용자 눈에는 검색 결과가 호출마다 흔들리는
     * 버그로 보인다(보완 리뷰 Important #5, 조율자 판정). {@code a.name, a.id} 로 정렬해 결정적인
     * 순서를 고정한다 — 이름이 같은 학원이 있을 수 있어 {@code id} 를 2차 키로 더한다. API_SPEC §1.8
     * 의 페이징 봉투(page/size/total_count)는 채택하지 않는다 — 이 화면은 가입 시 학원 1곳을 고르는
     * 용도라 전체 목록 열람을 허용할 이유가 없고, 봉투를 붙이면 비인증 호출자가 페이지를 넘겨가며
     * 전체 학원 목록을 훑어볼 수 있게 된다(조율자 판정, docs/planning/API_SPEC.md §2.1 갱신됨).
     *
     * <p>코드는 대소문자를 가리지 않는다(BR-053) — 대문자로만 발급되지만 사람이 휴대폰 자판으로 옮겨 적는다.
     */
    @Query("SELECT a FROM Academy a WHERE a.status = :status "
            + "AND (a.name LIKE CONCAT('%', :q, '%') ESCAPE '\\' "
            + "OR LOWER(a.code) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\') "
            + "ORDER BY a.name, a.id")
    List<Academy> searchByStatus(@Param("status") AcademyStatus status, @Param("q") String q, Pageable pageable);
}
