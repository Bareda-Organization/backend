package src.backend.global.security.access;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link AcademyScopeRepositoryConventionTest} 가 쓰는 <b>판정 전담</b> 술어 — 무엇이 학원 범위
 * 자원인지는 여기서 정하지 않는다.
 *
 * <p>{@link AcademyScopeScan}(수집)과 가른 축이 하나 더 있다. 이 클래스는 <b>"좁혀졌다"·"전건
 * 조회다" 의 정의</b>를 담고, 테스트 클래스는 <b>ERD §6.1 표와 그 표에 거는 단언</b>만 남긴다.
 * 셋은 바뀌는 계기가 각각 다르다 — 표는 사양이 바뀔 때, 술어는 회피 형태를 새로 발견할 때
 * (라운드 1 의 {@code ::} · 라운드 2 의 개행), 수집은 패키지 구조가 바뀔 때.
 *
 * <p>술어를 표와 한 파일에 두면 회피 형태를 하나 막을 때마다 ERD 표를 함께 읽어야 하고, 실제로
 * 그 누적이 클래스를 {@code reference.md §20.2} 기준의 두 배로 키웠다(리뷰 라운드 2 m-3).
 */
final class AcademyScopeRule {

    private AcademyScopeRule() {
    }

    /**
     * 학원 범위 저장소에 호출하면 격리가 통째로 빠지는 상속 메서드 — {@code JpaRepository} 가 전부 물려준다.
     *
     * <p>이름만 적고 접합자({@code .} · {@code ::})는 {@link #BULK_READ_JOINERS} 로 따로 순회한다 —
     * {@code repo.findAll()} 만 막고 {@code repo::findAll} 을 놓치면 회피가 <b>메서드 참조로 쓰는 것</b>
     * 하나로 끝난다.
     */
    private static final List<String> UNSCOPED_BULK_READS = List.of("findAll", "findAllById", "count");

    /** 저장소 필드와 메서드 이름을 잇는 두 형태 — 호출({@code .})과 메서드 참조({@code ::}). */
    private static final List<String> BULK_READ_JOINERS = List.of(".", "::");

    /**
     * 한 소스에서 주어진 저장소 필드의 전건 조회 사용을 전부 찾는다.
     *
     * <p>정규식이 <b>두 가지</b>를 함께 요구한다.
     * <ul>
     *   <li><b>낱말 경계</b>({@code \b}) — {@code findAll} 을 단순 부분 문자열로 찾으면
     *       {@code findAllByAcademyIdAndDeletedAtIsNull} 처럼 <b>좁혀진</b> 조회까지 위반으로 세어,
     *       규칙을 지킨 코드가 실패하고 결국 다음 사람이 검사를 꺼 버린다. 뒤쪽 경계가 그 오탐을 막는
     *       유일한 장치라 접합자 규칙을 넓힐 때도 유지 대상이다</li>
     *   <li><b>접합자 양옆의 공백 허용</b>({@code \s*}) — 셋을 인접으로만 찾으면 개행 한 번으로 검사가
     *       사라진다({@code studentRepository} 줄바꿈 {@code .findAll()}). 체인이 길어지면 개행이
     *       표준 서식이고 이 저장소도 이미 그 서식을 쓰므로, 회피가 <b>서식을 바꾸는 것</b> 하나로
     *       성립하던 구멍이다(리뷰 라운드 2 I-1)</li>
     * </ul>
     *
     * <p>남는 한계는 <b>공백이 아닌 것이 접합자 사이에 끼어드는 형태</b>다 — 필드와 점 사이의 블록
     * 주석, {@code Repository r = repo;} 로 받아 {@code r.findAll()} 을 부르는 별칭. 앞은 실사용 서식이
     * 아니고 뒤는 필드 이름을 따라가야 해 텍스트 판정의 범위 밖이다.
     */
    static List<String> bulkReadsIn(String body, String field, Path source) {
        List<String> found = new ArrayList<>();
        for (String bulkRead : UNSCOPED_BULK_READS) {
            for (String joiner : BULK_READ_JOINERS) {
                Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "\\s*"
                        + Pattern.quote(joiner) + "\\s*" + Pattern.quote(bulkRead) + "\\b").matcher(body);
                if (matcher.find()) {
                    found.add(source.getFileName() + ": " + field + joiner + bulkRead);
                }
            }
        }
        return found;
    }

    /**
     * 학원 조건이 걸려 있으면 좁혀진 것으로 센다 — 메서드 이름 · {@code @Query} 본문 · {@code @Param}
     * 셋 중 어디든 하나면 참이다. {@code @Query} 본문을 보는 축이 <b>부모 경유 조인</b>을 잡는다.
     *
     * <p><b>이름 기반 판정은 넓다</b> — {@code getName().contains("AcademyId")} 만 보므로
     * {@code findAllByAcademyIdIsNull} · {@code countByAcademyIdNot} 처럼 학원을 좁히는 것이 아니라
     * <b>뒤집는</b> 이름도 통과한다. 좁힘의 의미까지 이름으로 판정하려면 Spring Data 파서를 흉내내야
     * 하는데, 그 흉내가 틀리면 규칙을 지킨 조회가 실패한다. 현재 그런 메서드는 부재하고, 생기면
     * {@code AcademyScopeIsolationTest} 의 HTTP 왕복 대조가 결과 행으로 잡는다.
     *
     * <p><b>{@code @Query} 축은 본문을 파싱하지 않고 부분 문자열 유무만 본다</b> — 이름이
     * <b>조건절에</b> 있는지는 판정 대상 밖이라, {@code SELECT ... AS academyId} 같은 별칭이나
     * 네이티브 쿼리의 주석에 적기만 해도 참이 된다. 현재 {@code @Query} 가 3건뿐이고 전부 조건절에
     * 쓰므로 정밀도 손실이 관측되지 않을 뿐이다. <b>Phase 5·9 가 부모 조인을 늘리면</b> 조인 조건을
     * 실제로 걸지 않고 이름만 적어 통과시키는 회피가 성립하므로, 그 시점에 조건절 파싱으로 좁히거나
     * 부모 조인 저장소를 {@link AcademyScopeExempt} 처럼 명시 표시로 옮기는 판단이 필요하다.
     */
    static boolean isNarrowedByAcademy(Method method) {
        if (method.getName().contains("AcademyId")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query != null && (query.value().contains("academyId") || query.value().contains("academy_id"))) {
            return true;
        }
        return Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation instanceof Param param && param.value().equals("academyId"));
    }
}
