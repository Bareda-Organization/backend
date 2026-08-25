package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * {@link AuthUser} 컴팩트 생성자의 불변식 단위 테스트 — 지금까지 이 불변식을 실제로 발생시키는
 * 테스트가 없었다(리뷰 라운드 1 Important #8). {@code academyId} 불변식은 학원 격리(Task 5)가
 * "system_admin 이 아니면 academyId 가 반드시 있다"를 전제로 판정 로직을 짤 예정이라, 그 전제를
 * 여기서 고정해 둔다.
 */
class AuthUserTest {

    @Test
    void system_admin_이_아닌_역할에_academyId_가_없으면_생성이_실패한다() {
        assertThatThrownBy(() -> new AuthUser(1L, null, Role.PARENT, AccountStatus.ACTIVE))
                .as("ck_account_academy_scope — system_admin 이 아닌 역할은 academyId 가 null 일 수 없다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void system_admin_은_academyId_없이_생성된다() {
        assertThatCode(() -> new AuthUser(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE))
                .as("system_admin 은 플랫폼 전역 범위라 academyId 가 없어도 정상 생성돼야 한다")
                .doesNotThrowAnyException();
    }
}
