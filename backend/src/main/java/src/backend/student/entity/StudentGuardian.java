package src.backend.student.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.user.entity.User;

/**
 * 학생 ↔ 보호자(학부모 User) 의 N:M 연결.
 * 한 학생에 여러 보호자, 한 보호자가 여러(형제자매) 학생을 가질 수 있다.
 */
@Entity
@Table(name = "student_guardian",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "guardian_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentGuardian extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id")
    private User guardian;

    private String relation;   // 예: "모", "부", "조부"

    @Builder
    public StudentGuardian(Student student, User guardian, String relation) {
        this.student = student;
        this.guardian = guardian;
        this.relation = relation;
    }
}
