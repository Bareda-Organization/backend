package src.backend.academy.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.global.persistence.AcademyCount;

/** {@link AcademyStaff} 영속성 접근. */
public interface AcademyStaffRepository extends JpaRepository<AcademyStaff, Long> {

    /**
     * 학원의 관계자 수를 상태별로 센다 — 정원 판정({@code AcademyStaffQuota})의 선검사가 쓰는 유일한 조회다.
     *
     * <p>상태를 인자로 받는 이유는 정원이 "행이 몇 개인가" 가 아니라 <b>"재직자가 몇 명인가"</b> 이기
     * 때문이다(Ruling 139) — 퇴사 행은 남으므로 상태를 가리지 않고 세면 교체가 영구히 막힌다.
     */
    long countByAcademyIdAndStatus(Long academyId, StaffStatus status);

    /** 학원 상세(API_SPEC §6.3 {@code staff_accounts[]})가 쓰는 관계자 목록 — 퇴사 이력도 함께 보인다. */
    List<AcademyStaff> findAllByAcademyIdOrderByIdAsc(Long academyId);

    /**
     * 목록 조회(API_SPEC §6.1 {@code staff_count})가 쓰는 학원별 재직 관계자 수.
     *
     * <p>한 페이지의 학원 전부를 한 번에 센다 — 학원마다 {@link #countByAcademyIdAndStatus} 를 부르면
     * 한 페이지(최대 100건)가 질의 100건이 된다.
     */
    @Query("SELECT s.academyId AS academyId, COUNT(s) AS total FROM AcademyStaff s "
            + "WHERE s.academyId IN :academyIds AND s.status = :status GROUP BY s.academyId")
    List<AcademyCount> countByAcademyIdInGroupedByAcademyId(@Param("academyIds") Collection<Long> academyIds,
            @Param("status") StaffStatus status);
}
