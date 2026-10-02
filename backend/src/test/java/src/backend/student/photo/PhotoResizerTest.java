package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.student.photo.spec.StudentPhoto;

/**
 * R46-KFIXBE K-3 — 업로드 사진은 긴 변 512px 로 줄여 저장한다. 목록 한 화면이 행마다 사진 한 장(약 4MB)을 받던 것을 줄이는 수정이다.
 * ImageIO 가 못 읽거나(깨진 파일) 이미 작으면 원본 그대로이고, 휴대폰 사진의 회전 정보(EXIF)는 지켜서 옆으로 누운 사진을 만들지 않는다.
 * WebP 는 JDK 가 못 읽어 원본(최대 5MB)으로 남던 형식인데, 읽기 전용 디코더(TwelveMonkeys)를 더해 같은 규칙으로 줄인다(R47 Ruling 745).
 */
class PhotoResizerTest {

    private static final int RED = 0xFF0000;

    private static final int GREEN = 0x00FF00;

    private static final int BLUE = 0x0000FF;

    private static final int YELLOW = 0xFFFF00;

    /** 4000 × 3000 회색조 사진의 원본 해상도 래스터 크기(바이트) — BR-302 시험의 기준선이다. */
    private static final long ORIGINAL_RASTER_BYTES = 4000L * 3000L;

    @Test
    void 큰_PNG_는_긴_변이_512px_로_줄고_형식은_그대로다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(png(2000, 1000)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).as("가로세로 비율을 지킨다").isEqualTo(256);
        assertThat(shrunk.extension()).isEqualTo("png");
    }

    @Test
    void 큰_JPEG_도_같은_크기로_줄고_JPEG_로_남는다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(jpeg(700, 1400, 0)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(512);
        assertThat(shrunk.extension()).isEqualTo("jpg");
    }

    /** JDK 에 WebP 쓰기가 없어 줄인 결과는 JPEG 로 저장한다 — 확장자도 {@code jpg} 로 바뀐다(응답 Content-Type 은 확장자로 정해진다). */
    @Test
    void 큰_WebP_는_긴_변이_512px_로_줄어_JPEG_로_저장된다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(resource("wide-1200x800.webp")));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).as("가로세로 비율을 지킨다(800 × 512 / 1200 ≈ 341)").isEqualTo(341);
        assertThat(shrunk.extension()).isEqualTo("jpg");
    }

    /** 투명 배경 WebP 를 JPEG 로 쓰면 JDK 가 못 쓰거나 배경이 검게 변한다 — 알파가 있으면 PNG 로 저장한다. */
    @Test
    void 투명_배경_WebP_는_알파를_지킨_PNG_로_저장된다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(resource("alpha-1024x512.webp")));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).isEqualTo(256);
        assertThat(image.getColorModel().hasAlpha()).as("알파 채널이 남는다").isTrue();
        assertThat(shrunk.extension()).isEqualTo("png");
    }

    @Test
    void 이미_작거나_못_읽는_사진은_원본_그대로다() throws Exception {
        StudentPhoto small = StudentPhoto.of(png(400, 300));
        assertThat(PhotoResizer.shrink(small)).as("512px 이하는 다시 인코딩하지 않는다").isSameAs(small);

        // PNG 서명만 있고 본문이 깨진 파일 — 검증(서명)은 통과하지만 ImageIO 가 못 읽는다
        StudentPhoto broken = StudentPhoto.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
        assertThat(PhotoResizer.shrink(broken)).isSameAs(broken);
    }

    /**
     * BR-360 — 읽을 리더가 하나도 없으면 원본을 저장하되 <b>경고를 남긴다</b>. WebP 플러그인이 실행 환경에서 등록되지 않으면(서비스 로더가 못 찾는 경우)
     * 로그 없이 최대 5MB 원본이 저장돼, 시험은 초록인 채 줄이기가 통째로 꺼져 있어도 운영에서 알 길이 없었다.
     */
    @Test
    void 읽을_리더가_없으면_경고를_남기고_원본_그대로다() {
        Logger logger = (Logger) LoggerFactory.getLogger(PhotoResizer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            // 서명 검증(StudentPhoto.of)을 거치지 않고 만든다 — 어떤 리더도 알아보지 못하는 바이트열
            StudentPhoto unreadable = new StudentPhoto("webp", "이 바이트열은 이미지가 아니다".getBytes(StandardCharsets.UTF_8));

            assertThat(PhotoResizer.shrink(unreadable)).isSameAs(unreadable);

            assertThat(logs.list).as("경고가 한 건 남는다").singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).as("형식과 크기를 싣는다").contains("webp")
                        .contains(String.valueOf(unreadable.content().length));
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    /** 세로로 찍은 휴대폰 사진은 픽셀이 가로로 저장되고 EXIF 가 "90도 돌려 보라" 고 적는다 — 그것을 지우고 줄이면 사진이 누워 보인다. */
    @Test
    void EXIF_회전_정보를_따라_돌려서_줄인다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(jpeg(1400, 700, 6)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).as("90도 돌려 세로 사진이 된다").isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(512);
    }

    /**
     * BR-321 — 회전 방향을 <b>픽셀 색</b>으로 본다. 치수만 보면 6(시계)과 8(반시계)을 맞바꿔도, 180도 분기를 지워도 통과한다. 원본은 네 분면이
     * 서로 다른 색이라(왼쪽 위 빨강 · 오른쪽 위 초록 · 왼쪽 아래 파랑 · 오른쪽 아래 노랑) 돌린 결과의 네 분면 색이 방향을 말해 준다.
     * 기대 색은 EXIF 규격에서 손으로 따라간 값이다 — 6 은 시계 90도, 8 은 반시계 90도, 3 은 180도.
     */
    @Test
    void EXIF_회전이_없으면_네_분면_색이_그대로다() throws Exception {
        assertQuadrants(shrunkQuadrants(0, false), RED, GREEN, BLUE, YELLOW);
    }

    @Test
    void EXIF_6_은_시계_방향_90도로_돌린다() throws Exception {
        assertQuadrants(shrunkQuadrants(6, false), BLUE, RED, YELLOW, GREEN);
    }

    @Test
    void EXIF_8_은_반시계_방향_90도로_돌린다() throws Exception {
        assertQuadrants(shrunkQuadrants(8, false), GREEN, YELLOW, RED, BLUE);
    }

    @Test
    void EXIF_3_은_180도로_돌린다() throws Exception {
        assertQuadrants(shrunkQuadrants(3, false), YELLOW, BLUE, GREEN, RED);
    }

    /** EXIF 는 리틀엔디언(II)도 표준 표기다 — 오프셋(4바이트)과 태그·값(2바이트)이 바이트 순서만 다르고 결과는 같아야 한다. */
    @Test
    void 리틀엔디언_EXIF_도_같은_방향으로_돌린다() throws Exception {
        assertQuadrants(shrunkQuadrants(6, true), BLUE, RED, YELLOW, GREEN);
        assertQuadrants(shrunkQuadrants(8, true), GREEN, YELLOW, RED, BLUE);
    }

    /** 거울상 회전(2·4·5·7)은 다루지 않는다 — 줄이다 틀리느니 원본을 둔다. */
    @Test
    void 거울상_EXIF_사진은_원본_그대로다() throws Exception {
        StudentPhoto mirrored = StudentPhoto.of(jpeg(1400, 700, 2));

        assertThat(PhotoResizer.shrink(mirrored)).isSameAs(mirrored);
    }

    /**
     * 신뢰할 수 없는 업로드를 디코딩하는 것은 처음이다 — 5MB 이하 파일도 해상도가 크면 디코딩에 수백 MB 가 든다(압축 폭탄). 픽셀 수가 상한을
     * 넘으면 디코딩하지 않고 원본을 둔다.
     */
    @Test
    void 픽셀_수가_상한을_넘는_사진은_디코딩하지_않고_원본_그대로다() throws Exception {
        BufferedImage huge = new BufferedImage(6500, 6500, BufferedImage.TYPE_BYTE_GRAY); // 42,250,000 픽셀 > 상한 16,000,000
        StudentPhoto original = StudentPhoto.of(encode(huge, "png"));

        assertThat(PhotoResizer.shrink(original)).isSameAs(original);
    }

    /** BR-302 — 상한은 1,600만 화소다(WebP 플러그인이 전체 프레임을 디코딩해 요청 하나가 화소당 약 5B 를 잡는다). 그 바로 위 크기는 원본을 둔다. */
    @Test
    void 천육백만_화소를_넘는_사진은_디코딩하지_않고_원본_그대로다() throws Exception {
        StudentPhoto original = grayScale(4250, 4000, "png"); // 17,000,000 픽셀 > 상한 16,000,000

        assertThat(PhotoResizer.shrink(original)).isSameAs(original);
    }

    /** 새 디코더(WebP 플러그인)도 신뢰할 수 없는 입력을 읽는다 — 파일은 수 KB 인데 픽셀이 4,225만인 WebP 는 헤더 크기만 보고 디코딩하지 않는다. */
    @Test
    void 픽셀_수가_상한을_넘는_WebP_도_디코딩하지_않고_원본_그대로다() throws Exception {
        StudentPhoto original = StudentPhoto.of(resource("bomb-6500x6500.webp"));

        assertThat(PhotoResizer.shrink(original)).isSameAs(original);
    }

    /**
     * BR-302 — 큰 사진을 줄일 때 원본 해상도 래스터를 만들지 않는다. 1,200만 화소(휴대폰 사진 규모) 회색조 사진의 원본 래스터는 12MB 다 —
     * 줄이는 동안 그 스레드가 할당한 바이트가 그보다 적어야 한다(원본을 통째로 디코딩하면 래스터 하나만으로 이 값에 닿고, 줄이기 중간 복사본까지
     * 합하면 두 배를 넘는다). 정수 배로 건너뛰며 읽으면(하위 표본 추출) 디코딩 결과가 긴 변 1,024px 근처에 머문다.
     */
    @Test
    void 큰_PNG_를_줄여도_원본_해상도_래스터를_만들지_않는다() throws Exception {
        assertThat(allocatedWhileShrinking(grayScale(4000, 3000, "png"))).isLessThan(ORIGINAL_RASTER_BYTES);
    }

    @Test
    void 큰_JPEG_를_줄여도_원본_해상도_래스터를_만들지_않는다() throws Exception {
        assertThat(allocatedWhileShrinking(grayScale(4000, 3000, "jpg"))).isLessThan(ORIGINAL_RASTER_BYTES);
    }

    /** BR-302 — 동시에 줄이는 사진 수에 상한이 있다. 자리가 없으면 대기 한도까지 기다리고, 넘으면 업로드를 막지 않고 원본을 둔다. */
    @Test
    void 줄이기_자리가_없고_대기_한도를_넘으면_원본_그대로다() throws Exception {
        StudentPhoto big = StudentPhoto.of(png(2000, 1000));

        PhotoResizer.RESIZE_SLOTS.acquire(PhotoResizer.MAX_CONCURRENT_RESIZES);
        try {
            assertThat(PhotoResizer.shrink(big, Duration.ofMillis(100))).isSameAs(big);
        } finally {
            PhotoResizer.RESIZE_SLOTS.release(PhotoResizer.MAX_CONCURRENT_RESIZES);
        }
        assertThat(PhotoResizer.shrink(big)).as("자리가 풀리면 다시 줄인다").isNotSameAs(big);
    }

    @Test
    void 줄이기_자리가_나면_기다리던_사진을_줄인다() throws Exception {
        StudentPhoto big = StudentPhoto.of(png(2000, 1000));

        PhotoResizer.RESIZE_SLOTS.acquire(PhotoResizer.MAX_CONCURRENT_RESIZES);
        CompletableFuture<StudentPhoto> waiting;
        try {
            waiting = CompletableFuture.supplyAsync(() -> PhotoResizer.shrink(big, Duration.ofSeconds(30)));
            Thread.sleep(200);
            assertThat(waiting).as("자리가 없는 동안은 줄이기를 시작하지 않는다").isNotDone();
        } finally {
            PhotoResizer.RESIZE_SLOTS.release(PhotoResizer.MAX_CONCURRENT_RESIZES);
        }

        assertThat(decode(waiting.get(30, TimeUnit.SECONDS)).getWidth()).isEqualTo(512);
    }

    /** 같은 형식의 단색 회색조 사진 — 파일은 작아도 디코딩하면 {@code width × height} 바이트다. */
    private static StudentPhoto grayScale(int width, int height, String format) throws IOException {
        return StudentPhoto.of(encode(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), format));
    }

    /** 줄이는 동안 이 스레드가 할당한 바이트 — 첫 호출의 클래스 적재·플러그인 탐색 비용은 작은 사진 한 장으로 미리 치른다. */
    private static long allocatedWhileShrinking(StudentPhoto photo) throws IOException {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "이 JVM 은 스레드별 할당량을 못 잰다");
        PhotoResizer.shrink(StudentPhoto.of(png(1000, 600)));
        long threadId = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(threadId);
        PhotoResizer.shrink(photo);
        return threads.getThreadAllocatedBytes(threadId) - before;
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = PhotoResizerTest.class.getResourceAsStream("/student/photo/" + name)) {
            return stream.readAllBytes();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    /** 지정한 EXIF 회전값({@code orientation} 0 이면 EXIF 없음)을 단 JPEG — 픽셀은 {@code width × height} 그대로 저장한다. */
    private static byte[] jpeg(int width, int height, int orientation) throws IOException {
        return jpeg(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), orientation, false);
    }

    /** EXIF 회전값(0 이면 EXIF 없음)과 TIFF 바이트 순서(빅엔디안 {@code MM} · 리틀엔디언 {@code II})를 고른 JPEG. */
    private static byte[] jpeg(BufferedImage image, int orientation, boolean littleEndian) throws IOException {
        byte[] plain = encode(image, "jpg");
        if (orientation == 0) {
            return plain;
        }
        // TIFF 헤더 + IFD0 항목 1개(태그 0x0112, SHORT, 1개, 값) + 다음 IFD 없음
        byte[] app1Body = littleEndian
                ? new byte[] {'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0,
                        (byte) orientation, 0, 0, 0, 0, 0, 0, 0}
                : new byte[] {'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1,
                        0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        int segmentLength = app1Body.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(segmentLength >> 8);
        out.write(segmentLength & 0xFF);
        out.write(app1Body);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /** 네 분면이 서로 다른 단색인 사진 — 왼쪽 위 빨강 · 오른쪽 위 초록 · 왼쪽 아래 파랑 · 오른쪽 아래 노랑. */
    private static BufferedImage quadrants(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean left = x < width / 2;
                boolean top = y < height / 2;
                image.setRGB(x, y, (top ? (left ? RED : GREEN) : (left ? BLUE : YELLOW)));
            }
        }
        return image;
    }

    /** 네 분면 사진에 회전값을 달아 줄인 결과 — 원본은 1400 × 700 이라 6·8 은 256 × 512, 3 은 512 × 256 이 된다. */
    private static BufferedImage shrunkQuadrants(int orientation, boolean littleEndian) throws IOException {
        return decode(PhotoResizer.shrink(StudentPhoto.of(jpeg(quadrants(1400, 700), orientation, littleEndian))));
    }

    /** 결과 사진 네 분면 한가운데의 색이 기대와 같은지 — JPEG 손실이 있어 채널별 오차를 허용하고, 분면 경계는 피해 중심을 잰다. */
    private static void assertQuadrants(BufferedImage image, int topLeft, int topRight, int bottomLeft, int bottomRight) {
        int width = image.getWidth();
        int height = image.getHeight();
        assertThat(image.getRGB(width / 4, height / 4)).as("왼쪽 위").satisfies(rgb -> assertCloseTo(rgb, topLeft));
        assertThat(image.getRGB(width * 3 / 4, height / 4)).as("오른쪽 위").satisfies(rgb -> assertCloseTo(rgb, topRight));
        assertThat(image.getRGB(width / 4, height * 3 / 4)).as("왼쪽 아래").satisfies(rgb -> assertCloseTo(rgb, bottomLeft));
        assertThat(image.getRGB(width * 3 / 4, height * 3 / 4)).as("오른쪽 아래")
                .satisfies(rgb -> assertCloseTo(rgb, bottomRight));
    }

    private static void assertCloseTo(int actual, int expected) {
        int distance = Math.abs(((actual >> 16) & 0xFF) - ((expected >> 16) & 0xFF))
                + Math.abs(((actual >> 8) & 0xFF) - ((expected >> 8) & 0xFF))
                + Math.abs((actual & 0xFF) - (expected & 0xFF));
        assertThat(distance).as("색 %06x 와 %06x 의 거리", actual & 0xFFFFFF, expected & 0xFFFFFF).isLessThan(120);
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(StudentPhoto photo) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(photo.content()));
    }
}
