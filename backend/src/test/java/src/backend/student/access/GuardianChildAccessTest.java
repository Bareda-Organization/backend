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
import src.backend.student.entity.Guardian;
import src.backend.student.repository.LinkedChild;

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

    /**
     * 타 학원 자녀는 <b>목록에도</b> 섞이지 않는다(§1.5).
     *
     * <p>위 "타 학원 보호자는 닿지 못한다" 는 단건 판정({@link GuardianChildAccess#assertLinkedChild})
     * 만 지난다 — 목록 쪽 학원 조건을 지워도 그 단언은 그대로 통과한다. 단건은 막는데 목록이 새는
     * 상태가 {@code ARCHITECTURE §6.1} 이 지목한 사고 지점이라 축을 따로 세운다.
     *
     * <p>학원을 넘는 연결 행을 <b>일부러 심어</b> 본다. 오늘 쓰기 경로
     * ({@code ChildLinkCommandService.requestLink} 의 {@code findByLoginIdAndAcademyId})가 같은 학원
     * 쌍만 허용해 이런 행은 실제로 태어나지 않는다 — 그래서 이 조건은 지금 아무것도 막지 않는 것처럼
     * 보이고, 그것이 리뷰에서 이 조건을 지워도 아무 시험이 물지 않은 이유다. 쓰기 경로가 훗날
     * 바뀌면(관리자 데이터 보정·별도 등록 경로) 이 조건이 유일한 저지선이 되고, 그때 무너져도
     * 화면에는 남의 학원 아이가 자녀 목록에 한 줄 늘어난 것으로만 보인다.
     *
     * <p>같은 학원 자녀가 <b>여전히 나오는 것</b>을 함께 본다. 없으면 목록을 통째로 비우는 구현이
     * "섞이지 않았다" 를 그대로 지나간다.
     */
    @Test
    void 타_학원_자녀는_자녀_목록에_섞이지_않는다() {
        long 학원B_자녀 = 학생_식별자(SeedFixtures.STUDENT_B1_LOGIN_ID);
        학원을_넘는_연결을_심는다(GUARDIAN_SIBLINGS_ACCOUNT, 학원B_자녀);

        Guardian guardian = guardianChildAccess.requireGuardian(보호자(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A));

        assertThat(guardianChildAccess.linkedChildren(guardian))
                .extracting(LinkedChild::getStudentId)
                .as("연결 행이 학원을 넘어 존재해도 목록은 자기 학원으로 좁혀야 한다")
                .contains(SIBLING_1_ID)
                .doesNotContain(학원B_자녀);
    }

    private AuthUser 보호자(long accountId, Long academyId) {
        return new AuthUser(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }

    /** 로그인 아이디로 학생 식별자를 얻는다 — 시드 번호를 손으로 옮겨 적으면 시드가 바뀔 때 조용히 어긋난다. */
    private long 학생_식별자(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT s.id FROM student s JOIN account a ON a.id = s.account_id WHERE a.login_id = ?",
                Long.class, loginId);
    }

    /**
     * 학원을 넘는 연결 행을 직접 넣는다 — 쓰기 경로로는 만들 수 없는 상태라 SQL 로 심는다.
     *
     * <p>조회 API 로 대조군을 만들면 그 API 가 잘못돼도 대조군이 함께 틀려 아무것도 못 잡는다.
     */
    private void 학원을_넘는_연결을_심는다(long guardianAccountId, long studentId) {
        jdbcTemplate.update("INSERT INTO guardian_student (guardian_id, student_id, linked_at)"
                        + " VALUES ((SELECT id FROM guardian WHERE account_id = ?), ?, now())",
                guardianAccountId, studentId);
    }

    private void 연결을_해제한다(long guardianAccountId, long studentId) {
        jdbcTemplate.update("UPDATE guardian_student SET unlinked_at = now()"
                        + " WHERE guardian_id = (SELECT id FROM guardian WHERE account_id = ?) AND student_id = ?",
                guardianAccountId, studentId);
    }
}
