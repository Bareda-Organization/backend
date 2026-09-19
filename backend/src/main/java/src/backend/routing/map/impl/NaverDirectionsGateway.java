package src.backend.routing.map.impl;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.MapRouteUnavailableException;
import src.backend.routing.map.spec.RoadLeg;

/**
 * NCP Direction 15 를 <b>구간 하나</b>만큼 부르고 보호를 거는 자리 — 재시도·서킷이 걸리는 유일한 지점이다.
 *
 * <p>{@link NaverDirectionsClient} 와 빈을 가르는 이유는 Spring AOP 때문이다. 같은 클래스 안에서
 * 부르면 프록시를 거치지 않아 {@code @Retry}·{@code @CircuitBreaker} 가 <b>한 번도 걸리지 않고</b>,
 * 설정에는 값이 적혀 있으니 아무도 눈치채지 못한다.
 *
 * <p>2026-09-13 Directions 5({@code /map-direction/v1/driving})에서 15
 * ({@code /map-direction-15/v1/driving})로 전환했다(BE-R1 목표 6) — 경로만 바뀌고 응답 스키마는 같다
 * ({@code route.traoptimal[].summary.distance/duration},
 * <a href="https://api.ncloud-docs.com/docs/ai-naver-mapsdirections15-driving">NCP 공식 문서</a> 확인).
 *
 * <p>⚠ 대가 — 일일 할당량 60,000 → 3,000(Directions 5 → 15). 옛 경로가 상한 밖 경유지도 받아 결과에
 * 반영하지만(2026-09-13 실 API 확인) 문서 미보장 동작이라 채택하지 않음 — 근거는 {@code
 * application.yml} 의 {@code max-waypoints} 주석.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.routing.map.provider", havingValue = "naver", matchIfMissing = true)
public class NaverDirectionsGateway {

    /** {@code resilience4j.*.instances} 의 키 — 재시도·서킷이 같은 이름을 공유한다. */
    public static final String RESILIENCE_INSTANCE = "mapRoute";

    private static final String DRIVING_PATH = "/map-direction-15/v1/driving";

    private static final String KEY_ID_HEADER = "x-ncp-apigw-api-key-id";

    private static final String KEY_HEADER = "x-ncp-apigw-api-key";

    /** NCP 가 경유지를 가르는 문자 — URI 에 그대로 실을 수 없어 인코딩을 거친다. */
    private static final String WAYPOINT_SEPARATOR = "|";

    /** 연결 자체가 안 되는 경우의 상한 — 응답 대기 상한은 호출자가 요청마다 주입한다. */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    /** NCP 는 소요 시간을 밀리초로 준다. */
    private static final int MILLIS_PER_SECOND = 1000;

    private final WebClient webClient;

    private final String baseUrl;

    private final String keyId;

    private final String key;

    /**
     * 공유 {@code webClient} 빈을 쓰지 않고 여기서 따로 만든다 — 그쪽은 응답 타임아웃이 5초로
     * 고정돼 있어, 배치가 주입한 긴 타임아웃이 조용히 5초로 잘린다. 주입한 값과 실제로 기다리는
     * 값이 갈리면 {@code ARCHITECTURE §8.3} 의 "배치는 길게" 가 설정에만 남는다.
     */
    public NaverDirectionsGateway(WebClient.Builder builder,
            @Value("${app.routing.map.naver.base-url}") String baseUrl,
            @Value("${app.routing.map.naver.key-id:}") String keyId,
            @Value("${app.routing.map.naver.key:}") String key) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        this.webClient = builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.key = key;
    }

    /**
     * 구간 하나의 도로 값을 {@code segment.size() - 1} 개로 돌려준다.
     *
     * <p><b>{@code @Bulkhead} 는 동시 호출 수를 막는다</b>({@code TECH_DECISIONS §8} ·
     * {@code ARCHITECTURE §9.4}) — 상한을 넘은 호출은 <b>기다리지 않고 거부</b>된다
     * ({@code max-wait-duration: 0}). 대기를 두면 호출자가 주입한 타임아웃 예산 밖의 시간이 앞에
     * 붙어, 관리자가 승인 화면에서 얼마를 기다릴지를 yml 값이 정하게 된다. 거부는 단발 실패와 같은
     * 경로로 흡수되어 직선거리 근사가 된다 — 서킷 개방과 달리 {@code ON_DEMAND} 라도 오류가 아니다.
     *
     * <p><b>{@code fallbackMethod} 가 {@code @Retry} 쪽에 있어야 한다.</b> 두 애스펙트의 순서는
     * Retry 가 바깥 · CircuitBreaker 가 안쪽으로 고정돼 있다({@code order} 2147483642 · 2147483643).
     * 안쪽에 fallback 을 걸면 {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
     * 이 바깥 Retry 에 닿기 전에 {@link MapRouteUnavailableException} 으로 바뀌어,
     * {@code ignore-exceptions} 가 그것을 알아보지 못한다 — <b>서킷이 열려 있는데도 재시도가 돌아</b>
     * 열린 서킷을 세 번 두드리고 {@code wait-duration} 만큼 응답만 늦어진다. 공급자에는 닿지 않고
     * 응답 코드도 그대로라 어느 기능 시험에도 드러나지 않는다.
     *
     * <p>⚠ <b>도달한 호출 수만으로는 이 배치를 가릴 수 없다</b>(2026-08-29 실측) — 폴백이 값을
     * 돌려주지 않고 예외를 던지므로 두 배치 모두 {@code max-attempts} 만큼 공급자를 부른다. 순서를
     * 고정하는 것은 {@code NaverDirectionsResilienceTest} 의 <b>재시도 없이 실패한 호출 수</b>
     * 단언이고, 호출 수 단언이 잡는 것은 재시도가 아예 안 걸린 상태다.
     *
     * <p>⚠ <b>인접한 두 지점이 같으면 부르기 전에 걷어낸다</b>(R18 A 목표 1·3, 2026-09-19 실 API
     * 확인). NCP 는 그런 요청을 {@code 출발지와 도착지가 동일합니다}(400)로 통째로 거절하는데,
     * 등원 노선의 첫 정차지처럼 <b>출발 기준점과 그 자리에서 타는 학생의 승차지가 같은 자리인
     * 것은 정상적인 배차</b>라 흔히 일어난다 — {@code RunConfirmationService} 가 등원 origin 을
     * 노선의 첫 정차지 좌표로 잡고, 그 정차지에 학생이 있으면 같은 좌표가 정차지 목록에도 들어가
     * {@code [origin, 그 정차지, ...]} 로 중복된다. 걷어낸 자리는 거리·시간 0 인 leg 로 되돌려
     * ({@link #expand}) {@code segment.size() - 1} 개를 그대로 지킨다.
     *
     * @throws MapRouteUnavailableException 공급자에 닿지 못한 전부 — 타임아웃 · 5xx · 서킷 개방
     */
    @Bulkhead(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<RoadLeg> legsOf(List<GeoPoint> segment, Duration timeout) {
        List<GeoPoint> distinct = withoutConsecutiveDuplicates(segment);
        if (distinct.size() < 2) {
            return zeroLegs(segment.size() - 1);
        }
        DrivingResponse response = webClient.get()
                .uri(drivingUri(distinct))
                .header(KEY_ID_HEADER, keyId)
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(DrivingResponse.class)
                .timeout(timeout)
                .block();
        Traoptimal traoptimal = traoptimalOf(response);
        List<RoadLeg> legs = legsOf(distinct, traoptimal.summary());
        return withPath(expand(segment, legs), pathOf(traoptimal));
    }

    /** 연속 중복 지점을 걷어낸다 — {@link #expand} 가 반대 방향으로 되돌린다. */
    static List<GeoPoint> withoutConsecutiveDuplicates(List<GeoPoint> segment) {
        List<GeoPoint> distinct = new ArrayList<>();
        distinct.add(segment.getFirst());
        for (int i = 1; i < segment.size(); i++) {
            if (!samePoint(segment.get(i), segment.get(i - 1))) {
                distinct.add(segment.get(i));
            }
        }
        return distinct;
    }

    /**
     * 값이 같은 위경도인지 — {@code BigDecimal.equals} 는 스케일이 다르면 값이 같아도 다르다고
     * 답해 이 판정에 쓰면 안 된다({@code numeric(9,6)} 컬럼끼리는 대개 스케일이 같지만, 그 전제에
     * 기대지 않는다).
     */
    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return a.lat().compareTo(b.lat()) == 0 && a.lng().compareTo(b.lng()) == 0;
    }

    /** 지점 전부가 한 자리면 부를 이유가 없다 — 거리·시간 0 인 leg 로 바로 답한다. */
    static List<RoadLeg> zeroLegs(int count) {
        return Collections.nCopies(count, new RoadLeg(0, 0, List.of()));
    }

    /**
     * {@link #withoutConsecutiveDuplicates} 로 걷어낸 자리에 거리·시간 0 인 leg 를 끼워 넣어
     * {@code segment.size() - 1} 개로 되돌린다.
     */
    static List<RoadLeg> expand(List<GeoPoint> segment, List<RoadLeg> distinctLegs) {
        List<RoadLeg> legs = new ArrayList<>(segment.size() - 1);
        int distinctIndex = 0;
        for (int i = 1; i < segment.size(); i++) {
            if (samePoint(segment.get(i), segment.get(i - 1))) {
                legs.add(new RoadLeg(0, 0, List.of()));
            } else {
                legs.add(distinctLegs.get(distinctIndex++));
            }
        }
        return legs;
    }

    /**
     * 구간별 실측값이 있으면 그대로 쓰고, 없으면 직선거리 비율로 대체한다.
     *
     * <p>NCP 는 경유지가 있는 요청에 <b>구간별 실측값을 그대로 준다</b>({@code summary.waypoints[]}
     * 가 시작점→첫 경유지, 경유지→다음 경유지 순으로, {@code summary.goal} 이 마지막 경유지→도착지
     * 구간을 담는다 — 2026-09-19 실 API 확인, {@code NaverDirectionsClientLiveTest}). 예전 주석의
     * "경유지별 값을 안 준다" 는 <b>직선거리 비율 배분판을 그대로 두려고 아무도 실 응답을 확인하지
     * 않은 채 남은 추정</b>이었다 — {@link StraightLineLegs#distribute} 는 그 추정 위에 지어진
     * 근사값이었고, 지도 API 가 정상 응답한 경우에도 구간별 거리·시간이 근사값이 되는 원인이었다.
     *
     * <p>경유지가 없어 구간이 하나뿐이면(총합 자체가 그 구간의 실측값이라 배분이 필요 없다) 또는
     * 응답이 예상과 다른 개수를 주면(방어적으로) 기존 직선거리 비율로 되돌아간다.
     */
    static List<RoadLeg> legsOf(List<GeoPoint> segment, Summary summary) {
        int expectedLegs = segment.size() - 1;
        List<RoadLeg> measured = measuredLegsOf(summary, expectedLegs);
        if (measured != null) {
            return measured;
        }
        return StraightLineLegs.distribute(segment, (int) Math.round(summary.distance()),
                (int) Math.round(summary.duration() / MILLIS_PER_SECOND));
    }

    /**
     * {@code summary.waypoints[]} + {@code summary.goal} 을 구간 목록으로 옮긴다 — 개수가 안 맞거나
     * 값이 없으면 {@code null} 을 돌려줘 호출부가 직선거리 비율로 대체하게 한다.
     *
     * <p>거리(m)는 이미 정수라 반올림 오차가 없지만, 시간은 밀리초라 구간마다 따로 반올림하면 합이
     * 총합에서 벗어난다({@code 1500ms + 1500ms → 2s + 2s = 4s} 인데 총합은 {@code 3000ms → 3s}).
     * 누적값을 반올림하고 직전에 배정한 값을 빼는 방식({@link StraightLineLegs#distribute} 와 같은
     * 기법)으로 마지막 구간에서 합이 총합과 정확히 맞아떨어지게 한다.
     */
    private static List<RoadLeg> measuredLegsOf(Summary summary, int expectedLegs) {
        List<WaypointLeg> waypoints = summary.waypoints();
        GoalLeg goal = summary.goal();
        if (waypoints == null || waypoints.size() != expectedLegs - 1
                || goal == null || goal.distance() == null || goal.duration() == null) {
            return null;
        }
        List<RoadLeg> legs = new ArrayList<>(expectedLegs);
        double cumulativeMeters = 0;
        double cumulativeMillis = 0;
        long assignedMeters = 0;
        long assignedSeconds = 0;
        for (WaypointLeg waypoint : waypoints) {
            cumulativeMeters += waypoint.distance();
            cumulativeMillis += waypoint.duration();
            long meters = Math.round(cumulativeMeters);
            long seconds = Math.round(cumulativeMillis / MILLIS_PER_SECOND);
            legs.add(new RoadLeg((int) (meters - assignedMeters), (int) (seconds - assignedSeconds), List.of()));
            assignedMeters = meters;
            assignedSeconds = seconds;
        }
        cumulativeMeters += goal.distance();
        cumulativeMillis += goal.duration();
        long meters = Math.round(cumulativeMeters);
        long seconds = Math.round(cumulativeMillis / MILLIS_PER_SECOND);
        legs.add(new RoadLeg((int) (meters - assignedMeters), (int) (seconds - assignedSeconds), List.of()));
        return legs;
    }

    /**
     * 세그먼트 전체 도로 좌표를 <b>첫 leg 에만</b> 싣고 나머지는 비운다(R15 T1 목표 2·3).
     *
     * <p>공급자가 경유지별 좌표를 안 주므로(거리·시간처럼 배분할 근거가 없다) leg 마다 나눠 담을
     * 수 없다 — 대신 세그먼트당 좌표 뭉치 하나로 두고, {@link RoadRoute#roadPath()} 가 이어 붙이면서
     * 세그먼트 경계의 겹친 좌표를 걷어낸다. leg 가 비어 있으면(구간 분할이 0개 지점을 만들 수 없어
     * 실제로는 나지 않지만 방어적으로) 그대로 돌려준다.
     */
    private static List<RoadLeg> withPath(List<RoadLeg> legs, List<GeoPoint> path) {
        if (legs.isEmpty()) {
            return legs;
        }
        List<RoadLeg> result = new ArrayList<>(legs.size());
        RoadLeg first = legs.getFirst();
        result.add(new RoadLeg(first.distanceMeters(), first.durationSeconds(), path));
        for (int i = 1; i < legs.size(); i++) {
            RoadLeg leg = legs.get(i);
            result.add(new RoadLeg(leg.distanceMeters(), leg.durationSeconds(), List.of()));
        }
        return result;
    }

    /**
     * {@code route.traoptimal[].path} 를 {@link GeoPoint} 로 옮긴다 — <b>NCP 는 좌표를
     * {@code [경도, 위도]} 순으로 준다</b>(NCP 공식 문서 응답 예시 실측). 뒤집어 넣으면 좌표가
     * 지구 반대편(바다)으로 간다.
     */
    private static List<GeoPoint> pathOf(Traoptimal traoptimal) {
        List<List<BigDecimal>> path = traoptimal.path();
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<GeoPoint> points = new ArrayList<>(path.size());
        for (List<BigDecimal> point : path) {
            points.add(new GeoPoint(point.get(1), point.get(0)));
        }
        return points;
    }

    /**
     * 공급자에 닿지 못한 전부를 포트 예외 하나로 모은다 — 호출부가 원인별로 분기하지 않게 하기 위함이다.
     *
     * <p>{@code private} 이 아닌 것은 Resilience4j 가 리플렉션으로 찾기 때문이고, 시그니처가 원
     * 메서드 + {@link Throwable} 인 것도 그 규약이다.
     *
     * <p>⚠ <b>{@code public} 이어야 한다 — package-private 으로는 부족하다</b>(2026-08-29 실측,
     * resilience4j-spring6 2.3.0). {@code FallbackMethod.invoke()} 는 이 메서드의 {@link
     * java.lang.reflect.Method} 객체를 클래스당 하나로 캐시해 모든 호출이 공유한다. package-private
     * 이면 호출마다 {@code setAccessible(true)} 로 열었다가 {@code finally} 에서 다시 {@code false}
     * 로 되돌리는데(그 메서드가 public 이면 이 토글 자체가 생략된다), <b>동시에 여러 스레드가 같은
     * {@code Method} 객체에서 이 토글을 밟으면</b> 한 스레드가 {@code invoke()} 를 부르기 직전에 다른
     * 스레드가 이미 {@code false} 로 되돌려 놓아 {@link IllegalAccessException} 이 난다. 격벽이
     * 순간적으로 여러 건을 한꺼번에 거절할 때(동시 도래가 상한을 넘을 때)만 이 경합이 열리므로,
     * 순차 호출뿐인 서킷 개방 시험에서는 한 번도 걸리지 않았다 — {@code public} 이면 이 토글 자체가
     * 없어 경합도 없다.
     *
     * <p>⚠ <b>여기서 WARN 로그를 남긴다</b>(R18 A 목표 2). 이 메서드로 들어왔다는 것 자체가 이미
     * "공급자에 닿지 못했다" 는 뜻이라, 이후 호출부가 직선거리 근사로 넘어가든({@code
     * NaverDirectionsClient.route}) 서킷 개방을 그대로 되던지든({@code CallerPolicy.ON_DEMAND})
     * 운영에서는 똑같이 "지도 API 가 응답하지 않았다" 는 사실을 알아야 한다 — 지금까지는
     * {@code fallback_used} 가 DB 한 칸에만 남고 로그가 하나도 없어, 근사 경로가 쌓여도 아무도
     * 몰랐다.
     */
    public List<RoadLeg> unavailable(List<GeoPoint> segment, Duration timeout, Throwable cause) {
        log.warn("도로 경로 조회가 폴백으로 떨어졌다 — 지점 {}개, 원인: {}", segment.size(), cause.toString());
        throw new MapRouteUnavailableException("도로 경로 조회 실패: 지점 " + segment.size() + "개", cause,
                cause instanceof CallNotPermittedException);
    }

    /**
     * 첫 지점이 {@code start}, 마지막이 {@code goal}, 나머지가 {@code waypoints} 다 — NCP 는
     * <b>경도를 먼저</b> 적는다.
     *
     * <p><b>문자열을 이어 {@code URI.create} 로 넘기면 안 된다</b> — 경유지 구분자 {@code |} 는
     * URI 에 쓸 수 없는 문자라 {@code IllegalArgumentException} 이 나고, 그 예외가 폴백에 삼켜져
     * <b>"경유지가 있는 요청만 조용히 근사값이 되는"</b> 형태로 나타난다(이 저장소가 실제로 그
     * 상태였다). 그것을 막는 것은 {@link UriComponentsBuilder} 로 조립하는 것 자체다.
     *
     * <p>⚠ 부하를 지는 것은 {@code .encode()} 가 <b>아니다</b> — 이 입력에서는 {@code build().toUri()}
     * 와 결과가 같다(spring-web 7.0.8 실측: 둘 다 {@code %7C}). 좌표·호스트 밖의 문자가 섞일 때를
     * 위해 남겨 둔 것이고, {@code |} 처리의 근거로 읽으면 안 된다.
     */
    private URI drivingUri(List<GeoPoint> segment) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(baseUrl)
                .path(DRIVING_PATH)
                .queryParam("start", coordinate(segment.getFirst()))
                .queryParam("goal", coordinate(segment.getLast()));
        List<GeoPoint> middle = segment.subList(1, segment.size() - 1);
        if (!middle.isEmpty()) {
            url.queryParam("waypoints", middle.stream()
                    .map(NaverDirectionsGateway::coordinate)
                    .collect(Collectors.joining(WAYPOINT_SEPARATOR)));
        }
        return url.build().encode().toUri();
    }

    private static String coordinate(GeoPoint point) {
        return point.lng().toPlainString() + "," + point.lat().toPlainString();
    }

    /**
     * 경로를 찾지 못한 응답도 실패로 올린다 — 여기서 예외를 내면 위 {@code fallbackMethod} 를 거쳐
     * 직선거리 근사로 이어지고, 회차 하나가 경로 부재로 통째로 멈추지 않는다.
     *
     * <p>⚠ NCP 는 {@code option} 을 지정하지 않으면 {@code trafast}(최속)가 아니라
     * {@code traoptimal}(기본·실시간 반영)로 응답한다({@code Ruling 276} — 실 호출로 확인).
     * 예전에는 {@code trafast} 를 읽어 파싱이 항상 {@code null} 로 떨어졌고, 그 실패가 예외가 아니라
     * 바로 위 폴백(직선거리 근사)으로 조용히 흡수돼 <b>어댑터가 생긴 날부터 실 도로 경로가 한 번도
     * 쓰인 적이 없었다</b>.
     */
    private static Traoptimal traoptimalOf(DrivingResponse response) {
        if (response == null || response.route() == null || response.route().traoptimal() == null
                || response.route().traoptimal().isEmpty()) {
            throw new IllegalStateException("네이버 응답에 경로가 없다");
        }
        return response.route().traoptimal().getFirst();
    }

    /** NCP 응답 최상위. */
    record DrivingResponse(RouteWrapper route) {
    }

    /** {@code option} 을 지정하지 않았을 때의 기본 탐색 결과 묶음이 {@code traoptimal} 이다. */
    record RouteWrapper(List<Traoptimal> traoptimal) {
    }

    /** @param path {@code [[경도, 위도], ...]} — 경로를 구성하는 좌표(NCP 공식 문서 응답 예시). */
    record Traoptimal(Summary summary, List<List<BigDecimal>> path) {
    }

    /**
     * 총합과 함께 구간별 실측값도 온다(경유지가 있을 때만 — 2026-09-19 실 API 확인).
     *
     * @param waypoints 시작점→첫 경유지, 경유지→다음 경유지 순 구간값. 경유지가 없으면 {@code null}
     * @param goal      마지막 경유지(또는 경유지가 없으면 시작점)→도착지 구간값. 경유지가 없으면
     *                  {@code location}·{@code dir} 만 오고 {@code distance}·{@code duration} 은
     *                  {@code null} 이다
     */
    record Summary(double distance, double duration, List<WaypointLeg> waypoints, GoalLeg goal) {
    }

    /** 경유지 하나 직전 구간의 실측 거리(m)·시간(ms). */
    record WaypointLeg(double distance, double duration) {
    }

    /** 도착지 직전 구간의 실측 거리(m)·시간(ms) — 경유지가 없으면 둘 다 {@code null} 이다. */
    record GoalLeg(Double distance, Double duration) {
    }
}
