package src.backend.academy.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 학원당 재직 관계자 1명 정원(C-01 · ACAD-05·06)의 판정 — {@link AcademyStaffQuota}.
 *
 * <p>이 태스크에는 이 판정을 부르는 엔드포인트가 부재하다. 소비자는 T2({@code §6.5} 가입 승인)와
 * T3({@code §6.7} {@code status=active} 전환)이며, 둘이 <b>각자</b> 이 판정을 부른다. 그래서
 * 엔드포인트가 아니라 판정 지점 단위로 검증한다 — 소비자가 생긴 뒤에 검증하면 그때는 이미 두 태스크가
 * 각자 사본을 들고 있을 수 있다.
 */
@SpringBootTest
@Transactional
class AcademyStaffQuotaTest {

    @Autowired
    private AcademyStaffQuota academyStaffQuota;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 이미 재직자가 있는 학원의 추가 승인은 {@code 409 STAFF_QUOTA_EXCEEDED} 다(§6.5). */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA1', 'P3T1정원학원1', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA1'), 'p3t1q1a', 'x', '재직자', "
                    + "'010-0000-3001', 'staff', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA1'), 'p3t1q1b', 'x', '두번째', "
                    + "'010-0000-3002', 'staff', 'pending')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA1'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1q1a'), 'active')"
    })
    void 이미_active_관계자가_있는_학원에_두_번째를_붙이면_409_STAFF_QUOTA_EXCEEDED_다() {
        Long academyId = 학원_식별자("P3T1QUOTA1");
        Long secondAccountId = 계정_식별자("p3t1q1b");

        assertThatThrownBy(() -> academyStaffQuota.enforce(academyId,
                () -> academyStaffRepository.save(AcademyStaff.uponApproval(academyId, secondAccountId))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.STAFF_QUOTA_EXCEEDED);
    }

    /**
     * 퇴사(<b>{@code inactive}</b>)한 학원에는 새 관계자를 붙일 수 있다 — Ruling 139 를 고정하는 단언이다.
     *
     * <p>이것이 없으면 {@code academy_staff(academy_id)} 를 전체 UNIQUE 로 되돌려도 아무도 모른다.
     * 되돌린 스키마에서는 학원당 행이 평생 1개라 이 저장이 제약 위반으로 실패하고, 그 학원은 새
     * 관계자를 <b>영원히</b> 승인할 수 없게 된다.
     */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA2', 'P3T1정원학원2', '부산', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA2'), 'p3t1q2a', 'x', '퇴사자', "
                    + "'010-0000-3003', 'staff', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA2'), 'p3t1q2b', 'x', '후임자', "
                    + "'010-0000-3004', 'staff', 'pending')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA2'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1q2a'), 'inactive')"
    })
    void 퇴사로_inactive_가_된_학원에는_새_관계자를_붙일_수_있다() {
        Long academyId = 학원_식별자("P3T1QUOTA2");
        Long successorAccountId = 계정_식별자("p3t1q2b");

        assertThatCode(() -> academyStaffQuota.enforce(academyId,
                () -> academyStaffRepository.save(AcademyStaff.uponApproval(academyId, successorAccountId))))
                .doesNotThrowAnyException();

        assertThat(academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE))
                .as("퇴사 행은 남고 재직자만 1명이어야 한다")
                .isEqualTo(1);
        assertThat(academyStaffRepository.findAllByAcademyIdOrderByIdAsc(academyId))
                .as("퇴사 이력은 지우지 않는다(ERD §7.1) — 행이 2개로 남는다")
                .hasSize(2);
    }

    /**
     * 선검사를 지난 뒤 DB 가 거부하면 그 거부도 {@code 409} 로 나간다.
     *
     * <p>동시 요청 2건이 서로의 커밋을 보지 못한 채 <b>둘 다 선검사를 통과</b>하는 상황과 같은 형태를
     * 한 트랜잭션 안에서 결정적으로 만든다 — 호출 시점 재직자가 0명이라 선검사는 지나고, 두 행을 함께
     * 넣어 조건부 UNIQUE 만 남긴다. 이 단언이 없으면 정원 초과가 사용자에게 {@code 500} 으로 나가
     * "서버가 고장났다" 와 "정원이 찼다" 가 구별되지 않는다.
     */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA3', 'P3T1정원학원3', '대구', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA3'), 'p3t1q3a', 'x', '동시1', "
                    + "'010-0000-3005', 'staff', 'pending')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA3'), 'p3t1q3b', 'x', '동시2', "
                    + "'010-0000-3006', 'staff', 'pending')"
    })
    void 선검사를_지난_뒤_DB_가_거부해도_409_STAFF_QUOTA_EXCEEDED_로_옮겨진다() {
        Long academyId = 학원_식별자("P3T1QUOTA3");
        Long first = 계정_식별자("p3t1q3a");
        Long second = 계정_식별자("p3t1q3b");

        assertThatThrownBy(() -> academyStaffQuota.enforce(academyId, () -> {
            academyStaffRepository.save(AcademyStaff.uponApproval(academyId, first));
            return academyStaffRepository.save(AcademyStaff.uponApproval(academyId, second));
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.STAFF_QUOTA_EXCEEDED);
    }

    /**
     * <b>상태 전이</b>로 정원을 넘겨도 {@code 409} 다 — 새 행을 넣는 경로와 별개의 축이다(§6.7).
     *
     * <p>앞 단언(두 행 삽입)만으로는 {@code enforce} 의 flush 가 고정되지 않는다. 삽입은
     * {@code IDENTITY} 키를 받으려고 {@code save()} 시점에 이미 DB 로 나가서, flush 를 지워도
     * 위반이 같은 {@code try} 안에서 터진다 — <b>실측으로 확인한 사실</b>이다(변형 M2 가 살아남았다).
     *
     * <p>변경 감지로만 DB 에 닿는 이 경로는 다르다. flush 가 없으면 {@code UPDATE} 가 커밋 시점까지
     * 미뤄져 위반이 {@code enforce} 밖에서 터지고, 그때는 이미 {@code 409} 로 옮길 자리를 지나쳐
     * {@code 500} 이 나간다. T3 의 {@code status=active} 전환이 바로 이 경로다.
     */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA6', 'P3T1정원학원6', '인천', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA6'), 'p3t1q6a', 'x', '복직1', "
                    + "'010-0000-3008', 'staff', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA6'), 'p3t1q6b', 'x', '복직2', "
                    + "'010-0000-3009', 'staff', 'active')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA6'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1q6a'), 'inactive')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA6'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1q6b'), 'inactive')"
    })
    void 상태_전이가_선검사를_지난_뒤_DB_에_거부돼도_409_STAFF_QUOTA_EXCEEDED_로_옮겨진다() {
        Long academyId = 학원_식별자("P3T1QUOTA6");
        List<AcademyStaff> 퇴사자들 = academyStaffRepository.findAllByAcademyIdOrderByIdAsc(academyId);

        // 호출 시점 재직자가 0명이라 선검사는 지난다 — 동시 전환 2건이 각자 선검사를 통과한 것과 같은 형태다.
        assertThatThrownBy(() -> academyStaffQuota.enforce(academyId, () -> {
            퇴사자들.forEach(AcademyStaff::reinstate);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.STAFF_QUOTA_EXCEEDED);
    }

    /** 정원과 무관한 제약 위반은 {@code 409 STAFF_QUOTA_EXCEEDED} 로 바뀌지 않는다 — 원인을 감추지 않는다. */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA4', 'P3T1정원학원4', '광주', 'active')",
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1QUOTA5', 'P3T1정원학원5', '광주', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA4'), 'p3t1q4a', 'x', '겸직시도', "
                    + "'010-0000-3007', 'staff', 'active')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1QUOTA4'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1q4a'), 'active')"
    })
    void 계정_중복_연결_위반은_정원_초과로_바뀌지_않는다() {
        // P3T1QUOTA5 는 관계자가 없는 학원이라 정원 선검사를 지난다 — 남는 제약은 계정 UNIQUE 뿐이다.
        Long otherAcademyId = 학원_식별자("P3T1QUOTA5");
        Long alreadyLinked = 계정_식별자("p3t1q4a");

        assertThatThrownBy(() -> academyStaffQuota.enforce(otherAcademyId,
                () -> academyStaffRepository.save(AcademyStaff.uponApproval(otherAcademyId, alreadyLinked))))
                .as("uk_academy_staff_account 위반을 정원 초과로 옮기면 '이미 다른 학원의 관계자' 라는 원인이 사라진다")
                .isNotInstanceOf(BusinessException.class);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * {@code @Sql} 로 심은 학원의 식별자를 코드로 되찾는다 — 자동 생성 키라 SQL 문에 적을 수 없고,
     * 삽입 순서로 유추하면(앞 행 id + 1) 시퀀스가 어디서 시작하든 맞는다는 보장이 사라진다.
     */
    private Long 학원_식별자(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
    }

    private Long 계정_식별자(String loginId) {
        return accountRepository.findByLoginId(loginId).orElseThrow().getId();
    }
}
