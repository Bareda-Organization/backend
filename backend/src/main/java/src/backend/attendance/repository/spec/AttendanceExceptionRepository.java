package src.backend.attendance.repository.spec;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.attendance.entity.AttendanceException;

/**
 * 결석·휴원 신고 저장소. 명단 스킵 판정(승인된 신고가 있으면 그 날짜 배정에서 제외)에 쓴다.
 */
public interface AttendanceExceptionRepository extends JpaRepository<AttendanceException, Long> {

    List<AttendanceException> findByStudentIdAndTargetDate(Long studentId, LocalDate targetDate);

    List<AttendanceException> findByStudentIdInOrderByTargetDateDesc(List<Long> studentIds);

    List<AttendanceException> findByTenantIdOrderByTargetDateDesc(Long tenantId);
}
