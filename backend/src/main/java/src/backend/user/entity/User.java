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

    /**
     * 인적 정보 갱신 — null 은 "그대로"다.
     * ⚠️ User–Tenant 는 N:M 이라 이 값은 이 사람이 속한 모든 학원에서 함께 바뀐다.
     */
    public void updateProfile(String name, String phone, String photoUrl) {
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (photoUrl != null) {
            this.photoUrl = photoUrl;
        }
    }

    /** 로그인 ID(이메일) 변경 — 중복 검사는 호출자가 먼저 한다. */
    public void changeEmail(String email) {
        this.email = email;
    }

    /** 비밀번호 변경 — 인코딩된 값만 넣는다(평문 금지). */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
