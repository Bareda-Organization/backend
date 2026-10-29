package src.backend.student.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 수신자 이름은 한 곳에서 정해진다 — 보호자 수신자를 읽는 조회는 하나뿐이고, 그 조회는 계정 이름이 아니라 학원이 관리하는 보호자
 * 이름을 낸다. 조회가 둘이면 같은 보호자가 알림 종류에 따라 다른 이름으로 기록된다.
 */
@SpringBootTest
@Transactional
class GuardianRecipientProjectionTest {

    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    private static final long SIBLING_1_ID = 1L;

    @Autowired
    private GuardianStudentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 보호자_수신자를_읽는_조회는_하나뿐이다() {
        assertThat(수신자_조회_메서드()).hasSize(1);
    }

    @Test
    void 수신자_이름은_계정_이름이_아니라_보호자_이름이다() throws Exception {
        jdbcTemplate.update("UPDATE account SET name = '계정쪽이름' WHERE id = ?", SIBLINGS_GUARDIAN_ACCOUNT);
        jdbcTemplate.update("UPDATE guardian SET name = '보호자쪽이름' WHERE account_id = ?", SIBLINGS_GUARDIAN_ACCOUNT);

        for (Method query : 수신자_조회_메서드()) {
            List<?> recipients = (List<?>) invoke(query);
            assertThat(recipients).isNotEmpty();
            assertThat(recipients).allSatisfy(recipient -> assertThat(nameOf(recipient)).isEqualTo("보호자쪽이름"));
        }
    }

    private Object invoke(Method query) throws IllegalAccessException, InvocationTargetException {
        return query.invoke(repository, 1L, List.of(SIBLING_1_ID));
    }

    private static String nameOf(Object recipient) {
        return ((GuardianAccountRecipient) recipient).getName();
    }

    /** 반환형이 {@code List<GuardianAccountRecipient>} 인 저장소 메서드 전부. */
    private static List<Method> 수신자_조회_메서드() {
        return Arrays.stream(GuardianStudentRepository.class.getDeclaredMethods())
                .filter(method -> method.getGenericReturnType() instanceof ParameterizedType type
                        && type.getActualTypeArguments()[0] == GuardianAccountRecipient.class)
                .toList();
    }
}
