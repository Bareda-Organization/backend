package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.dto.StopSuggestResponse;
import testsupport.db.ConnectionCountingConfig;

/**
 * 같은 요청 안에서 같은 행을 되풀이해 읽지 않는다 — 자녀 지목은 보호자를 한 번만 고르고, 승하차지 자동완성은 후보가 여럿이어도
 * 근처 승하차지를 한 번의 질의로 가져온다. 질의 수는 준비된 SQL 문장을 직접 센다.
 */
@SpringBootTest
@Transactional
@Import(ConnectionCountingConfig.class)
class StudentLookupQueryCountTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    @Autowired
    private LinkedChildLookup linkedChildLookup;

    @Autowired
    private StopSearchQueryService stopSearchQueryService;

    @Test
    void 자녀를_지목하면_보호자를_한_번만_고른다() {
        AuthUser parent = new AuthUser(5L, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE, false);
        ConnectionCountingConfig.reset();

        linkedChildLookup.linkedChild(parent, Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID));

        assertThat(ConnectionCountingConfig.preparedStatements())
                .filteredOn(sql -> sql.contains(" from guardian ") && sql.contains("account_id"))
                .as("보호자 조회 SQL").hasSize(1);
    }

    @Test
    void 자동완성_후보가_여럿이어도_근처_승하차지_질의는_한_번이다() {
        AuthUser staff = new AuthUser(2L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE, false);
        ConnectionCountingConfig.reset();

        StopSuggestResponse response = stopSearchQueryService.suggest(staff, "서울시 테스트로");

        assertThat(response.items()).as("스텁은 도로명만 치면 번지 3개를 후보로 낸다").hasSizeGreaterThanOrEqualTo(3);
        assertThat(ConnectionCountingConfig.preparedStatements())
                .filteredOn(sql -> sql.contains(" from stop "))
                .as("승하차지 조회 SQL").hasSize(1);
    }
}
