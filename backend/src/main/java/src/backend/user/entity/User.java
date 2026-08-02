package src.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;

/**
 * 로그인 주체(사람). 역할·소속 학원은 여기에 두지 않고 UserTenantRole 로 표현한다.
 * (학부모가 여러 학원에 자녀를 둘 수 있으므로 User–Tenant 는 N:M)
 * 'user' 는 예약어라 테이블명은 app_user.
 */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    private String phone;

    private String photoUrl;   // 프로필 사진 URL. 업로드 API 없이 문자열만 둔다(D-G)

    @Builder
    public User(String email, String password, String name, String phone, String photoUrl) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.photoUrl = photoUrl;
    }
}
