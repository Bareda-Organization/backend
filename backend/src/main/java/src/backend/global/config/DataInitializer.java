package src.backend.global.config;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 로컬 개발용 데모 시드. local 프로파일에서만 실행되며, 이미 학원이 있으면 건너뛴다(멱등).
 * 프론트 frontend/lib/simulation.js 의 학원(한빛/가온/미래)·정류장·버스·학생 구성과 맞춘다.
 * 비밀번호는 모두 "password".
 */
@Configuration
@Profile("local")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String PASSWORD = "password";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(TenantRepository tenantRepository,
                           UserRepository userRepository,
                           UserTenantRoleRepository userTenantRoleRepository,
                           RouteRepository routeRepository,
                           StopRepository stopRepository,
                           BusRepository busRepository,
                           StudentRepository studentRepository,
                           StudentGuardianRepository studentGuardianRepository,
                           PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (tenantRepository.count() > 0) {
            log.info("[seed] 학원 데이터가 이미 있어 시드를 건너뜁니다");
            return;
        }
        log.info("[seed] 데모 시드 데이터 생성 시작");

        // ── 학원 3곳 (좌표는 routing depot 기준점 — 한빛은 기존 "학원" 정류장 좌표 근방) ──
        Tenant hanbit = tenantRepository.save(Tenant.builder().name("한빛학원").lat(37.5075).lng(127.0355).build());
        Tenant gaon = tenantRepository.save(Tenant.builder().name("가온에듀").lat(37.4980).lng(127.0276).build());
        tenantRepository.save(Tenant.builder().name("미래코딩").lat(37.5145).lng(127.0300).build());

        // ── 역할별 계정 (비밀번호 공통) ──
        User studentUser = createUser("student@school.com", "김민준", "010-0000-0001");
        User parentUser = createUser("parent@school.com", "이부모", "010-0000-0002");
        User driverUser = createUser("driver@school.com", "박기사", "010-0000-0003");
        User adminUser = createUser("admin@school.com", "한빛관리자", "010-0000-0004");
        User platformUser = createUser("platform@school.com", "플랫폼관리자", "010-0000-0005");

        grant(studentUser, hanbit, Role.STUDENT);
        grant(parentUser, hanbit, Role.PARENT);
        grant(driverUser, hanbit, Role.DRIVER);
        grant(adminUser, hanbit, Role.ACADEMY_ADMIN);
        grant(platformUser, null, Role.PLATFORM_ADMIN);   // 플랫폼 관리자는 전역 역할(tenant 없음)

        // ── 한빛학원 노선·정류장 ──
        Route routeA = routeRepository.save(Route.builder()
                .tenant(hanbit).name("하원 A노선").assignCapacity(25).build());
        stopRepository.save(Stop.builder().route(routeA).name("정류장 A").seq(1).lat(37.5010).lng(127.0275).build());
        stopRepository.save(Stop.builder().route(routeA).name("정류장 B").seq(2).lat(37.5045).lng(127.0310).build());
        stopRepository.save(Stop.builder().route(routeA).name("학원").seq(3).lat(37.5075).lng(127.0355).build());
        Stop stopA = stopRepository.findByRouteIdOrderBySeqAsc(routeA.getId()).get(0);
        Stop stopB = stopRepository.findByRouteIdOrderBySeqAsc(routeA.getId()).get(1);

        // 배정 정원 2인 노선 — 학생 3명 배정으로 초과 경고 데모(기능9)
        Route routeB = routeRepository.save(Route.builder()
                .tenant(hanbit).name("하원 B노선").assignCapacity(2).build());

        // ── 한빛학원 버스 ──
        Bus bus3 = busRepository.save(Bus.builder()
                .tenant(hanbit).name("3호차").plateNumber("서울12가3456").seatCapacity(25)
                .driver(driverUser).route(routeA)
                .insuranceExpiry(LocalDate.of(2026, 12, 31)).build());
        Bus bus1 = busRepository.save(Bus.builder()
                .tenant(hanbit).name("1호차").plateNumber("서울34나5678").seatCapacity(25)
                .route(routeB)  // 기사 미배차
                .insuranceExpiry(LocalDate.of(2026, 8, 15)).build());

        // ── 한빛학원 학생 ──
        // 3호차(정상): 김민준(학생 계정 연결), 이서연, 박도윤 — 하차지 좌표는 routing 데모용으로 서로 흩어지게 부여
        Student kimStudent = studentRepository.save(Student.builder()
                .tenant(hanbit).userId(studentUser.getId()).name("김민준")
                .assignedBus(bus3).boardingStop(stopA).build());
        kimStudent.updateDropoff("서울 서초구 자택", 37.4998, 127.0245);
        studentRepository.save(kimStudent);
        Student leeStudent = studentRepository.save(Student.builder()
                .tenant(hanbit).name("이서연").assignedBus(bus3).boardingStop(stopA).build());
        leeStudent.updateDropoff("서울 서초구 자택2", 37.5032, 127.0398);
        studentRepository.save(leeStudent);
        Student parkStudent = studentRepository.save(Student.builder()
                .tenant(hanbit).name("박도윤").assignedBus(bus3).boardingStop(stopB).build());
        parkStudent.updateDropoff("서울 강남구 자택", 37.5060, 127.0290);
        studentRepository.save(parkStudent);
        // 1호차(초과): 배정 정원 2인 노선에 3명 → overCapacity
        studentRepository.save(Student.builder()
                .tenant(hanbit).name("최지우").assignedBus(bus1).boardingStop(stopA).build());
        studentRepository.save(Student.builder()
                .tenant(hanbit).name("정하율").assignedBus(bus1).boardingStop(stopA).build());
        studentRepository.save(Student.builder()
                .tenant(hanbit).name("강서준").assignedBus(bus1).boardingStop(stopB).build());

        // ── 학부모 ↔ 자녀 연결(형제자매: 김민준·이서연) ──
        Student kim = studentRepository.findByUserId(studentUser.getId()).orElseThrow();
        Student lee = studentRepository.findByTenantId(hanbit.getId()).stream()
                .filter(s -> s.getName().equals("이서연")).findFirst().orElseThrow();
        studentGuardianRepository.save(StudentGuardian.builder().student(kim).guardian(parentUser).relation("모").build());
        studentGuardianRepository.save(StudentGuardian.builder().student(lee).guardian(parentUser).relation("모").build());

        // ── 가온에듀: 크로스 테넌트 조회용 최소 데이터(플랫폼 관리자 데모) ──
        Route gaonRoute = routeRepository.save(Route.builder()
                .tenant(gaon).name("가온 1노선").assignCapacity(25).build());
        busRepository.save(Bus.builder()
                .tenant(gaon).name("2호차").plateNumber("서울56다7890").seatCapacity(25)
                .route(gaonRoute).insuranceExpiry(LocalDate.of(2027, 3, 1)).build());

        log.info("[seed] 완료 — 로그인 계정(비번 {}): student@/parent@/driver@/admin@/platform@school.com", PASSWORD);
        log.info("[seed] 기사 승하차 기록 테스트: bus3 id={}, 김민준 학생 id={}", bus3.getId(), kim.getId());
    }

    private User createUser(String email, String name, String phone) {
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .name(name)
                .phone(phone)
                .build());
    }

    private void grant(User user, Tenant tenant, Role role) {
        userTenantRoleRepository.save(UserTenantRole.builder()
                .user(user).tenant(tenant).role(role).build());
    }
}
