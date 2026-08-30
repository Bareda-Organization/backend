package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AcademyScopeRule#conditionClauseContainsAcademyId} 를 <b>직접</b> 겨냥한 단위 시험
 * (Phase 7 이월 4 · Phase 8 목표 16).
 *
 * <p>{@link AcademyScopeRepositoryConventionTest} 는 이 술어를 프로덕션 {@code @Query} 를 거쳐
 * <b>간접</b>으로만 검증한다 — 결함을 심어야만 드러난다. 이 술어 자신이 틀리면 그것이 지키는
 * 학원 격리 전체가 조용히 무너지는데 그 간접 경로만으로는 아무도 못 본다. 그래서 여기서는 조건절
 * 문자열을 직접 넣어 <b>통과해야 하는 것과 걸러야 하는 것을 둘 다</b> 건다 — 한쪽만 걸면
 * "항상 {@code true} 를 반환하는 구현" 도 통과한다.
 */
class AcademyScopeRuleTest {

    @Test
    void 학원_식별자가_조건절에_그대로_있으면_통과한다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.academyId = :academyId"))
                .as("가장 흔한 형태 — camelCase 프로퍼티 경로")
                .isTrue();
    }

    /**
     * 스네이크 케이스 — <b>판정한 결과</b>: 통과다. JPQL 프로퍼티 경로는 원래 camelCase 만 쓰지만,
     * 이 저장소에 아직 없을 뿐 {@code nativeQuery = true} 로 실제 컬럼명({@code academy_id})을 쓰는
     * {@code @Query} 가 생길 수 있고, 그때도 이 술어가 계속 학원 조건을 잡아야 한다. 지금 받아 주는
     * 편이 다음 사람이 native 쿼리를 추가할 때 이 검사가 조용히 대상 밖이 되는 것보다 안전하다.
     */
    @Test
    void 스네이크_케이스_학원_식별자도_조건절에_있으면_통과한다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.academy_id = :academyId"))
                .as("nativeQuery 컬럼명 표기 대비 — camelCase 만 받으면 native 쿼리가 검사 대상 밖이 된다")
                .isTrue();
    }

    @Test
    void 조건절에_학원_식별자가_아예_없으면_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.id = :id"))
                .as("학원과 무관한 조건뿐이면 좁혀진 것이 아니다")
                .isFalse();
    }

    /**
     * 접두사만 같고 실제로는 다른 식별자 — {@code academyId} 뒤에 곧바로 다른 글자가 이어지면
     * 단어 경계가 없어 매치되지 않아야 한다. 단순 {@code contains} 였다면 이 컬럼도 참으로 셌을
     * 것이고, 그러면 진짜 학원 조건 없이 이 컬럼만 있는 조회가 격리된 것으로 잘못 통과한다.
     */
    @Test
    void 학원_식별자와_접두사만_같은_다른_컬럼은_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.academyIdentifier = :x"))
                .as("academyIdentifier 는 academyId 와 다른 컬럼이다 — 접두사 일치만으로 참이 되면 안 된다")
                .isFalse();
    }

    @Test
    void WHERE_절_밖의_학원_식별자는_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r.academyId AS academyId FROM Run r WHERE r.id = :id ORDER BY r.academyId"))
                .as("SELECT 별칭·ORDER BY 는 대상을 좁히는 조건이 아니라 결과의 모양이다")
                .isFalse();
    }

    /**
     * 주석 안에만 등장 — <b>판정한 결과</b>: 거른다. 조건을 지우면서 흔적만 주석으로 남기는 것이
     * 조건을 지키는 것으로 잘못 세면 안 된다. 지금 구현은 {@code WHERE} 절을 자르기 전에 블록·줄
     * 주석을 먼저 걷어내므로, 조건인 척하는 주석은 애초에 검사 대상 문자열에서 사라진다.
     */
    @Test
    void 주석_안에만_등장하면_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.id = :id /* AND r.academyId = :academyId */"))
                .as("주석은 조건이 아니다 — 지운 조건을 주석으로만 남겨도 좁혀진 것이 아니다")
                .isFalse();
    }

    @Test
    void 줄_주석_안의_학원_식별자도_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r WHERE r.id = :id -- AND r.academyId = :academyId\n"))
                .as("줄 주석도 블록 주석과 같은 근거로 거른다")
                .isFalse();
    }

    @Test
    void WHERE_자체가_없으면_거른다() {
        assertThat(AcademyScopeRule.conditionClauseContainsAcademyId(
                "SELECT r FROM Run r ORDER BY r.id"))
                .as("조건이 있는지 모를 때는 없는 쪽으로 판정해야 실패 방향이 과잉 거부가 된다")
                .isFalse();
    }
}
