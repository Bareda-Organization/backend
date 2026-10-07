package src.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.query.AuditQueryFilter;
import src.backend.audit.query.LoginHistoryQueryService;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.Role;

/**
 * 접속 이력의 해제 행 이름 조회({@code unblocked_by_name}, Ruling 846)가 <b>해제 행 수와 무관한 질의 수</b>로 도는지 본다 —
 * 행마다 계정을 읽으면 한 페이지(최대 100건)가 질의 100건이 된다.
 *
 * <p>행위자·대상 계정을 행마다 다르게 심고 영속성 컨텍스트를 비운 뒤 센다 — 같은 계정이거나 컨텍스트에 남아 있으면
 * 행마다 읽는 구현도 첫 수준 캐시에 맞아 질의가 늘지 않아 이 시험이 통과해 버린다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class LoginHistoryUnblockQueryCountTest {

    @Autowired
    private LoginHistoryQueryService loginHistoryQueryService;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 해제_행이_늘어도_이름을_읽는_질의_수는_그대로다() {
        Academy academy = academyRepository.save(Academy.register("R846QC01", "R846질의학원", "서울", null, null));
        심는다(academy, "a", OffsetDateTime.now().minusMinutes(30));
        long one = 질의_수(() -> loginHistoryQueryService.list(filter(academy)));

        for (String tag : new String[] { "b", "c", "d", "e" }) {
            심는다(academy, tag, OffsetDateTime.now().minusMinutes(20));
        }
        long five = 질의_수(() -> loginHistoryQueryService.list(filter(academy)));

        assertThat(five).as("해제 행 1건일 때 질의 %d → 5건일 때 %d — 행마다 계정을 읽고 있다", one, five).isEqualTo(one);
    }

    private void 심는다(Academy academy, String tag, OffsetDateTime at) {
        Account admin = accountRepository.save(Account.forSignup(academy.getId(), "r846adm" + tag,
                passwordEncoder.encode("password1234!"), "해제관리자" + tag, "010-8461-000" + tag.charAt(0) % 10, null,
                Role.STAFF));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "r846tgt" + tag,
                passwordEncoder.encode("password1234!"), "해제대상" + tag, "010-8462-000" + tag.charAt(0) % 10, null,
                Role.PARENT));
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), admin.getId(), admin.getLoginId(),
                target.getId(), null, at));
    }

    private AuditQueryFilter filter(Academy academy) {
        return new AuditQueryFilter(academy.getId(), null, null, null, null, null);
    }

    private long 질의_수(Supplier<?> call) {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        call.get();
        return statistics.getPrepareStatementCount();
    }
}
