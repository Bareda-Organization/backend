package src.backend.student.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 목표 8(이월 Ruling 117) — 학부모 → {@code GuardianStudent} 연결 확인이 <b>한 지점</b>에서 나는지
 * 고정한다.
 *
 * <p>HTTP 왕복이 아니라 판정 지점 자체를 부른다. 이 판정의 첫 소비자는 자녀 목록(§3.1)이고 자녀를
 * {@code {id}} 로 지목하는 경로(§3.7 요일별 주소)는 뒤따르는 태스크가 만든다 — 그때까지 이 단언이
 * 없으면 <b>판정은 있는데 아무도 검사하지 않는 상태</b>로 남고, 다음 사람이 그 위에 경로를 얹는다.
 *
 * <p>세 축을 각각 본다. ①연결된 자녀는 통과 ②연결이 아예 없는 자녀는 거부 ③<b>해제된</b> 연결도
 * 거부. ③이 없으면 {@code unlinked_at} 조건을 지운 구현이 ①②를 그대로 지나간다 — 그 상태의 증상은
 * "퇴원으로 관계가 끝난 옛 보호자가 자녀 정보를 계속 조회" 이고, 화면에는 아무 이상이 드러나지 않는다.
 */
@SpringBootTest
@Transactional
class GuardianChildAccessTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    /** 형제 S1·S2 의 보호자({@code parentA1}). */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    /** 학원 B 보호자({@code parentB1}) — 학원 격리 대조군. */
    private static final long GUARDIAN_ACADEMY_B_ACCOUNT = 9L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    /** 이 보호자와 연결된 적이 없는 학생(S3) — {@code parentA2} 쪽 자녀다. */
    private static final long OTHER_GUARDIANS_CHILD_ID = Long.parseLong(SeedFixtures.STUDENT_UNLINKED_ID);

    @Autowired
    private GuardianChildAccess guardianChildAccess;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 연결된_자녀를_지목하면_통과한다() {
        assertThatCode(() -> guardianChildAccess.assertLinkedChild(보호자(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A),
                SIBLING_1_ID))
                .as("연결된 자녀까지 막으면 학부모 화면 전체가 닫힌다 — 거부 단언만으로는 그 사고를 못 본다")
                .doesNotThrowAnyException();
    }

    /** 연결되지 않은 자녀는 {@code 403 FORBIDDEN}(§3.7 — 연결 부재 자녀). */
    @Test
    void 연결되지_않은_자녀를_지목하면_403_FORBIDDEN_이다() {
        assertThatThrownBy(() -> guardianChildAccess.assertLinkedChild(
                보호자(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A), OTHER_GUARDIANS_CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /**
     * 해제된 연결({@code unlinked_at})은 살아 있는 연결이 아니다.
     *
     * <p>같은 자녀를 <b>해제 전에는 통과</b>시키고 해제 후에 거부하는 것까지 한 시험에서 본다 —
     * 두 상태를 갈라 두면 "원래 못 만지는 자녀였다" 와 구별되지 않는다.
     */
    @Test
    void 해제된_연결의_자녀를_지목하면_403_FORBIDDEN_이다() {
        AuthUser guardian = 보호자(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A);
        assertThatCode(() -> guardianChildAccess.assertLinkedChild(guardian, SIBLING_1_ID))
                .doesNotThrowAnyException();

        연결을_해제한다(GUARDIAN_SIBLINGS_ACCOUNT, SIBLING_1_ID);

        assertThatThrownBy(() -> guardianChildAccess.assertLinkedChild(guardian, SIBLING_1_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 보호자 레코드가 없는 계정(기사)은 자녀 판정 이전에 막힌다(§1.11 {@code FORBIDDEN}). */
    @Test
    void 보호자가_아닌_계정은_403_FORBIDDEN_이다() {
        assertThatThrownBy(() -> guardianChildAccess.requireGuardian(
                new AuthUser(13L, ACADEMY_A, Role.DRIVER, AccountStatus.ACTIVE)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 자녀 목록도 같은 판정을 지난다 — 단건만 막고 목록이 새는 상태를 여기서 잡는다. */
    @Test
    void 자녀_목록은_해제되지_않은_연결만_돌려준다() {
        var guardian = guardianChildAccess.requireGuardian(보호자(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A));
        assertThat(guardianChildAccess.linkedChildren(guardian))
                .extracting(child -> child.getStudentId())
                .contains(SIBLING_1_ID);

        연결을_해제한다(GUARDIAN_SIBLINGS_ACCOUNT, SIBLING_1_ID);

        assertThat(guardianChildAccess.linkedChildren(guardian))
                .as("해제된 연결이 목록에 남으면 옛 보호자가 자녀를 계속 본다")
                .extracting(child -> child.getStudentId())
                .doesNotContain(SIBLING_1_ID);
    }

    /**
     * 남의 학원 보호자는 그 자녀에 닿지 못한다(§1.5).
     *
     * <p>연결 행이 학원을 넘어 만들어질 일은 없으나, 판정이 {@code guardian_id} 만 보고 학원 조건을
     * 빼면 이 방어가 <b>연결 생성 경로에만</b> 남는다 — 판정 지점 자신이 좁히는지 여기서 본다.
     */
    @Test
    void 타_학원_보호자는_이_학원_자녀에_닿지_못한다() {
        assertThatThrownBy(() -> guardianChildAccess.assertLinkedChild(
                보호자(GUARDIAN_ACADEMY_B_ACCOUNT, ACADEMY_B), SIBLING_1_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private AuthUser 보호자(long accountId, Long academyId) {
        return new AuthUser(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }

    private void 연결을_해제한다(long guardianAccountId, long studentId) {
        jdbcTemplate.update("UPDATE guardian_student SET unlinked_at = now()"
                        + " WHERE guardian_id = (SELECT id FROM guardian WHERE account_id = ?) AND student_id = ?",
                guardianAccountId, studentId);
    }
}
