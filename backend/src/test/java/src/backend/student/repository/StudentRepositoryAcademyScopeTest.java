package src.backend.student.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.global.common.SeedFixtures;
import src.backend.student.entity.Student;

/**
 * 학원 조건이 <b>저장소 쿼리 안에</b> 있음을 컨트롤러·서비스를 거치지 않고 보인다 — 호출부가 조건을
 * 빠뜨릴 자리 자체가 없다는 것이 이 태스크가 만든 형태다(ARCHITECTURE §6.1).
 *
 * <p>{@code AcademyScopeIsolationTest} 는 같은 사실을 HTTP 왕복으로 보지만, 그 경로는 컨트롤러가
 * 조건을 넘겨 준 결과일 수도 있다. 여기서 저장소를 직접 불러야 조건이 쿼리에 고정됐음이 갈린다.
 */
@SpringBootTest
class StudentRepositoryAcademyScopeTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 학원_범위_목록은_그_학원_학생만_반환한다() {
        List<Student> academyA = studentRepository.findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc(ACADEMY_A);

        assertThat(academyA).as("대상이 0건이면 아래 단언이 공허하게 통과한다").isNotEmpty();
        assertThat(academyA).extracting(Student::getAcademyId).containsOnly(ACADEMY_A);
    }

    /** 대조군이 실재해야 "0건" 이 격리의 결과임이 갈린다 — 원래 없는 행이 안 나온 것과 구별한다. */
    @Test
    void 타_학원_학생은_실재하지만_그_학원_범위에서만_나타난다() {
        List<Long> academyBIdsInDatabase = jdbcTemplate.queryForList(
                "SELECT id FROM student WHERE academy_id = ? AND deleted_at IS NULL", Long.class, ACADEMY_B);
        assertThat(academyBIdsInDatabase).isNotEmpty();

        List<Long> viaAcademyA = idsOf(ACADEMY_A);
        List<Long> viaAcademyB = idsOf(ACADEMY_B);

        assertThat(viaAcademyB).containsExactlyInAnyOrderElementsOf(academyBIdsInDatabase);
        assertThat(viaAcademyA).doesNotContainAnyElementsOf(academyBIdsInDatabase);
    }

    private List<Long> idsOf(Long academyId) {
        return studentRepository.findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc(academyId).stream()
                .map(Student::getId)
                .toList();
    }
}
