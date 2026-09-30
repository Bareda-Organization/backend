package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;

/**
 * 연결 코드 <b>발급</b> 쪽의 충돌·개수 제한(BR-215 — BR-085 가 입력 쪽 절반만 고쳤다).
 *
 * <p>입력 쪽은 "쓸 수 있는 후보가 둘이면 거부" 만 있어서, 우연히 두 학생의 코드가 같은 6자리가 되면 두 학부모가 모두
 * 정상 코드를 넣고도 거부된다. 발급 때 (a) 그 학생의 이전 살아 있는 코드를 만료시키고 (b) 학원 안 살아 있는 코드와
 * 겹치지 않게 뽑으면, 학생 한 명이 쥘 수 있는 살아 있는 코드가 1개로 묶이고 충돌이 생기지 않는다.
 */
@SpringBootTest
@Transactional
class ChildLinkIssueCodeTest {

    /** 계정이 붙은 학원 A 학생({@code studentA4}) — 발급 주체다. */
    private static final long STUDENT_A4_ACCOUNT = 10L;

    private static final long STUDENT_A4_ID = 4L;

    /** 같은 학원의 다른 학생 — 남의 살아 있는 코드를 심을 자리다. */
    private static final long OTHER_STUDENT_ID = 1L;

    @Autowired
    private ChildLinkCommandService childLinkCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 재발급하면_이전_코드는_만료되어_살아_있는_코드가_한_개다() {
        childLinkCommandService.issueCode(학생());
        childLinkCommandService.issueCode(학생());
        childLinkCommandService.issueCode(학생());

        Integer live = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM link_code WHERE student_id = ? AND used_at IS NULL AND expires_at >= now()",
                Integer.class, STUDENT_A4_ID);

        assertThat(live).as("발급을 반복해도 학생 한 명이 쥔 살아 있는 코드는 1개 — 코드 공간을 채울 수 없다")
                .isEqualTo(1);
    }

    @Test
    void 학원_안_다른_학생의_살아_있는_코드와_같은_번호는_피해서_발급한다() {
        jdbcTemplate.update("INSERT INTO link_code (student_id, code, expires_at, created_at) "
                + "VALUES (?, '123456', now() + interval '10 minutes', now())", OTHER_STUDENT_ID);
        ChildLinkCommandService target = AopTestUtils.getUltimateTargetObject(childLinkCommandService);
        Object original = ReflectionTestUtils.getField(target, "random");
        // 첫 6자리는 남의 코드 123456, 다음 6자리는 654321 을 뽑는다.
        ReflectionTestUtils.setField(target, "random", digits(1, 2, 3, 4, 5, 6, 6, 5, 4, 3, 2, 1));
        try {
            String issued = childLinkCommandService.issueCode(학생()).code();

            assertThat(issued).as("살아 있는 남의 코드와 같으면 다시 뽑아야 한다").isEqualTo("654321");
        } finally {
            ReflectionTestUtils.setField(target, "random", original);
        }
    }

    private static SecureRandom digits(int... sequence) {
        return new SecureRandom() {
            private int next;

            @Override
            public int nextInt(int bound) {
                return sequence[next++ % sequence.length];
            }
        };
    }

    private AuthUser 학생() {
        return new AuthUser(STUDENT_A4_ACCOUNT, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.STUDENT,
                AccountStatus.ACTIVE);
    }
}
