package src.backend.user.repository.spec;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.user.entity.User;

/**
 * 사용자(로그인 주체) 저장소. 로그인 시 이메일로 계정을 찾는다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
