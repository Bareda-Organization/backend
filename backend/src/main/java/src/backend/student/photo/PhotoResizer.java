package src.backend.student.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import src.backend.student.photo.spec.StudentPhoto;

/**
 * 업로드 사진을 긴 변 {@value #MAX_EDGE}px 로 줄인다(Ruling 705) — 학생 목록 한 화면이 행마다 사진 한 장(약 4MB)을 받아 60명이면
 * 약 228MB 가 전송됐다. 표시 크기는 작아 원본 해상도가 필요한 화면이 없다. 새 의존성 없이 JDK {@code ImageIO} 만 쓴다.
 *
 * <p><b>줄이지 못하면 원본 그대로다</b> — {@code ImageIO} 가 읽지 못하는 형식(CMYK JPEG · 깨진 파일), 이미 작은 사진, 디코딩이 너무 무거운 사진,
 * 다루지 않는 EXIF 회전. 줄이기가 업로드를 막는 이유가 되면 안 된다. 이미 저장된 파일은 바꾸지 않는다(개발 단계).
 *
 * <p><b>신뢰할 수 없는 입력을 디코딩하는 첫 자리</b>다 — 5MB 이하 파일도 해상도가 크면 디코딩에 수백 MB 가 든다(압축 폭탄). 헤더만 읽어 픽셀 수가
 * {@value #MAX_DECODE_PIXELS} 를 넘으면 디코딩하지 않는다. 그 안의 사진도 <b>원본 해상도 래스터를 만들지 않는다</b>(BR-302) — 정수 배로 건너뛰며
 * 읽어 디코딩 결과가 긴 변 {@code 2 × MAX_EDGE} 근처에 머문다. 줄이기는 JVM 전체에서 {@value #MAX_CONCURRENT_RESIZES}건까지만 동시에 돈다.
 *
 * <p><b>휴대폰 JPEG 의 EXIF 회전을 지킨다</b> — 세로 사진은 픽셀이 가로로 저장되고 EXIF 가 "돌려 보라" 고 적는다. 다시 인코딩하면 그 정보가 사라져
 * 사진이 누워 보인다. 회전 3 · 6 · 8 은 돌려서 줄이고, 거울상(2 · 4 · 5 · 7)은 드물어 원본을 둔다.
 *
 * <p><b>WebP 도 줄인다</b>(Ruling 745) — JDK 기본 {@code ImageIO} 는 못 읽어 원본(최대 5MB)이 그대로 저장됐다. 읽기 전용 플러그인
 * (TwelveMonkeys {@code imageio-webp})이 읽고, 쓰기는 JDK 에 없어 JPEG(투명 배경이면 PNG)로 저장한다. 같은 헤더 크기 검사가 디코딩 앞에 서 있다.
 */
public final class PhotoResizer {

    private static final Logger log = LoggerFactory.getLogger(PhotoResizer.class);

    /** 긴 변 상한(px) — 정책 상수라 코드에 둔다({@code StudentPhoto} 의 5MB 와 같은 이유). */
    static final int MAX_EDGE = 512;

    /**
     * 디코딩을 허용하는 최대 픽셀 수(BR-302) — 1,600만 화소. 일반 휴대폰 사진(약 1,200만)은 들어오고, 5MB 업로드 상한 안에서 이보다 큰 사진은 드물다.
     * 하위 표본 추출은 JPEG·PNG 의 메모리만 줄인다 — WebP 플러그인은 전체 프레임을 디코딩해(1,200만 화소 손실 WebP 에 힙 64MB 이상, 약 5B/화소 실측)
     * 4천만 화소였던 옛 상한에서는 요청 하나가 약 200MB 였다.
     */
    static final long MAX_DECODE_PIXELS = 16_000_000L;

    /**
     * 동시에 줄이는 사진 수 상한(BR-302) — 운영 최소 사양이 2 vCPU 이고 줄이기는 CPU 일이라 그 이상은 빨라지지 않고 메모리만 쌓인다. 업로드는
     * 학원 관계자의 드문 조작이라 평소에는 대기가 없다.
     */
    static final int MAX_CONCURRENT_RESIZES = 2;

    /**
     * 자리가 날 때까지 기다리는 최대 시간 — 줄이기는 트랜잭션 밖(컨트롤러 진입 단계, BR-322)에서 돌아 DB 연결은 쥐지 않지만 요청 스레드를 쥔다. 무한정 기다리면
     * 스레드가 마르므로 한도를 넘으면 원본을 저장한다(줄이기가 업로드를 막는 이유가 되면 안 된다).
     */
    private static final Duration RESIZE_WAIT_LIMIT = Duration.ofSeconds(10);

    /** 줄이는 중인 사진 수를 세는 자리 — 선착순(공정)으로 내준다. 시험이 자리를 모두 쥐어 대기·한도 초과 경로를 만든다. */
    static final Semaphore RESIZE_SLOTS = new Semaphore(MAX_CONCURRENT_RESIZES, true);

    private PhotoResizer() {
    }

    /** 업로드 파일의 바이트를 읽는 수단 — 서블릿 타입을 이 패키지로 끌어오지 않으려고 함수로 받는다. */
    @FunctionalInterface
    public interface PhotoContent {
        byte[] read() throws IOException;
    }

    /**
     * 크기를 먼저 확인하고 줄이기 자리를 <b>얻은 뒤에</b> 파일 바이트를 읽어 줄인 사진을 돌려준다(DA-10, Ruling 899) — 자리를 기다리는 요청마다 최대 5MB 를 힙에
     * 올려 두지 않는다. 대기 한도를 넘으면 그때 읽어 원본을 돌려준다(줄이기가 업로드를 막는 이유가 되면 안 된다).
     */
    public static StudentPhoto shrinkFrom(long size, PhotoContent content) throws IOException {
        return shrinkFrom(size, content, RESIZE_WAIT_LIMIT);
    }

    static StudentPhoto shrinkFrom(long size, PhotoContent content, Duration waitLimit) throws IOException {
        StudentPhoto.requireSize(size); // 자리를 기다리기 전에 크기부터 — 잘못된 업로드가 자리를 차지하지 않는다
        if (!acquireSlot(waitLimit)) {
            log.warn("[photo] 줄이기 자리를 {}초 안에 얻지 못해 원본을 저장한다", waitLimit.toSeconds());
            return StudentPhoto.of(content.read());
        }
        try {
            return shrinkHoldingSlot(StudentPhoto.of(content.read()));
        } finally {
            RESIZE_SLOTS.release();
        }
    }

    /** 줄인 사진을 돌려준다 — 줄이지 않기로 했거나 줄이지 못했으면(자리가 안 나는 경우 포함) 받은 객체 그대로다. */
    public static StudentPhoto shrink(StudentPhoto photo) {
        return shrink(photo, RESIZE_WAIT_LIMIT);
    }

    static StudentPhoto shrink(StudentPhoto photo, Duration waitLimit) {
        if (!acquireSlot(waitLimit)) {
            log.warn("[photo] 줄이기 자리를 {}초 안에 얻지 못해 원본을 저장한다", waitLimit.toSeconds());
            return photo;
        }
        try {
            return shrinkHoldingSlot(photo);
        } finally {
            RESIZE_SLOTS.release();
        }
    }

    /** 자리를 쥔 채 줄인다 — 줄이지 못하면 받은 객체 그대로다. */
    private static StudentPhoto shrinkHoldingSlot(StudentPhoto photo) {
        try {
            return resize(photo).orElse(photo);
        } catch (IOException | RuntimeException e) {
            log.warn("[photo] 사진을 줄이지 못해 원본을 저장한다", e);
            return photo;
        }
    }

    /** 자리를 얻으면 {@code true} — 대기 중 인터럽트되면 신호를 되살리고 {@code false} 다(업로드는 원본으로 이어진다). */
    private static boolean acquireSlot(Duration waitLimit) {
        try {
            return RESIZE_SLOTS.tryAcquire(waitLimit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Optional<StudentPhoto> resize(StudentPhoto photo) throws IOException {
        int orientation = "jpg".equals(photo.extension()) ? exifOrientation(photo.content()) : 1;
        if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7) {
            return Optional.empty();
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(photo.content()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                // 로그 없이 원본을 저장하면 리더 플러그인(WebP)이 실행 환경에서 등록되지 않아 줄이기가 통째로 꺼져도 알 길이 없다(BR-360)
                log.warn("[photo] 읽을 수 있는 리더가 없어 원본을 저장한다 — 형식={}, 크기={}B", photo.extension(), photo.content().length);
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return reduceWith(reader, photo, orientation);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 리더로 읽어 줄인다 — 이미 작거나 디코딩 상한을 넘는 사진은 줄이지 않는다(빈 값). */
    private static Optional<StudentPhoto> reduceWith(ImageReader reader, StudentPhoto photo, int orientation)
            throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (Math.max(width, height) <= MAX_EDGE || (long) width * height > MAX_DECODE_PIXELS) {
            return Optional.empty();
        }
        BufferedImage reduced = PhotoRaster.reduce(PhotoRaster.orient(
                reader.read(0, PhotoRaster.subsamplingFor(reader, width, height)), orientation));
        String extension = PhotoRaster.storedExtension(photo.extension(), reduced);
        return PhotoRaster.encode(reduced, extension).map(bytes -> new StudentPhoto(extension, bytes));
    }

    /** JPEG 의 EXIF 회전값(1~8) — 읽는 방법은 {@link ExifOrientation}. 깨진 구조는 예외를 던져 호출부가 원본을 둔다. */
    static int exifOrientation(byte[] jpeg) {
        return ExifOrientation.of(jpeg);
    }
}
