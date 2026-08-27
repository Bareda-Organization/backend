package src.backend.student.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;

/**
 * {@link WeeklyAddressRepository} 의 <b>학원 조건이 실제로 무는지</b>를 본다(ARCHITECTURE §6.1).
 *
 * <p>이 조건은 오늘 호출부에서 도달할 수 없다 — 두 호출부 모두 이미 학원으로 걸러진 학생에서 얻은
 * {@code academyId} 를 넘기기 때문이다. 그래서 쿼리의 {@code AND s.academyId = :academyId} 를
 * 무력화해도 기능 테스트 15건이 전부 초록이었다(게이트 리뷰 실측). <b>방어 종심은 그것이 물린다는
 * 단언이 있을 때만 방어다</b> — 없으면 다음에 추가되는 경로가 학원 확인을 빠뜨린 채 태어나고, 그때
 * 이 조회는 그대로 다른 학원의 주소를 돌려준다.
 *
 * <p>그래서 <b>쓰기 경로가 만들 수 없는 조합</b>을 저장소에 직접 물어본다 — 어느 학생의 주소를
 * 그 학생이 속하지 않은 학원으로 조회하는 것이다. 응답이 비는 것이 조건이 살아 있다는 유일한 증거다.
 */
@SpringBootTest
@Transactional
class WeeklyAddressRepositoryTest {

    /** 학원 A 학생 — 시드가 요일 7 × 방향 2 의 14칸을 깔아 두었다. */
    private static final long ACADEMY_A_STUDENT_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    /** 학원 B 학생({@code studentB1}) — 시드에 요일별 주소가 부재해 이 클래스가 심는 행만 잡힌다. */
    private static final long ACADEMY_B_STUDENT_ID = 6L;

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 학생의 소속 학원으로 물으면 나오고, 다른 학원으로 물으면 <b>비어야 한다</b>.
     *
     * <p>두 방향을 함께 보는 이유는 한쪽만으로는 판정이 서지 않기 때문이다. 빈 결과만 보면 조회가
     * 통째로 망가진 상태와 구별되지 않고, 나오는 것만 보면 학원 조건이 없는 상태와 구별되지 않는다.
     */
    @Test
    void 소속되지_않은_학원으로_물으면_그_학생의_주소가_보이지_않는다() {
        assertThat(weeklyAddressRepository.findAllByStudentIdAndAcademyId(ACADEMY_A_STUDENT_ID, ACADEMY_A))
                .as("시드가 깔아 둔 14칸이 보이지 않는다 — 이 상태에서는 아래 단언이 조회 고장과 구별되지 않는다")
                .isNotEmpty();

        assertThat(weeklyAddressRepository.findAllByStudentIdAndAcademyId(ACADEMY_A_STUDENT_ID, ACADEMY_B))
                .as("학원 A 학생의 주소가 학원 B 로 물었는데 나왔다 — 쿼리의 부모 조인 학원 조건이 빠졌다")
                .isEmpty();
    }

    /**
     * 방금 생긴 행에도 같은 조건이 걸린다 — 시드가 특별해서 통과하는 것이 아님을 가른다.
     *
     * <p>행을 SQL 로 직접 심는다. 쓰기 경로({@code WeeklyAddressStore})는 학생에서 얻은 학원만
     * 넘기므로 "학원을 넘는 조회 대상" 을 애플리케이션으로는 만들 수 없고, 만들 수 없는 것을 심어야
     * 조건이 무는지 볼 수 있다.
     */
    @Test
    void 직접_심은_행도_학생의_소속_학원으로만_조회된다() {
        jdbcTemplate.update("""
                INSERT INTO weekly_address (student_id, weekday, direction, address, verified)
                VALUES (?, 'mon', 'to_academy', ?, true)
                """, ACADEMY_B_STUDENT_ID, "서울시 격리시험로 100");

        assertThat(weeklyAddressRepository.findAllByStudentIdAndAcademyId(ACADEMY_B_STUDENT_ID, ACADEMY_B))
                .as("심은 행이 소속 학원으로도 보이지 않는다 — 조인 조건이 학생을 못 찾고 있다")
                .hasSize(1);

        assertThat(weeklyAddressRepository.findAllByStudentIdAndAcademyId(ACADEMY_B_STUDENT_ID, ACADEMY_A))
                .as("학원 B 학생의 주소가 학원 A 로 물었는데 나왔다 — 격리가 호출부에만 매달려 있다")
                .isEmpty();
    }
}
