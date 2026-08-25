package src.backend.academy.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
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
     * 전체 학원 목록을 훑어볼 수 있게 된다(조율자 판정, docs/API_SPEC.md §2.1 갱신됨).
     */
    @Query("SELECT a FROM Academy a WHERE a.status = :status "
            + "AND (a.name LIKE CONCAT('%', :q, '%') ESCAPE '\\' OR a.code LIKE CONCAT('%', :q, '%') ESCAPE '\\') "
            + "ORDER BY a.name, a.id")
    List<Academy> searchByStatus(@Param("status") AcademyStatus status, @Param("q") String q, Pageable pageable);
}
