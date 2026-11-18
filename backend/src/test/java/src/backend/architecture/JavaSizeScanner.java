package src.backend.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자바 소스의 크기를 센다 — 파일 줄 수와 메서드 본문 줄 수(CODE_CONVENTIONS §20.2). 문자열 · 주석을 비운 사본에서 중괄호 블록을 따라가며,
 * 타입 본문 바로 안에서 {@code )} 로 끝나는 머리를 가진 블록을 메서드로 본다. 본문 줄 수는 여는 줄과 닫는 줄 사이의 줄이다(빈 줄 · 주석 줄 포함).
 */
final class JavaSizeScanner {

    static final int CLASS_LIMIT = 200;

    static final int METHOD_LIMIT = 20;

    /** 한도를 넘은 곳 — {@code symbol} 은 파일 전체면 {@code *}, 메서드면 그 이름이다. */
    record Offender(String path, String symbol, int lines) {

        @Override
        public String toString() {
            return path + " " + symbol + " " + lines;
        }
    }

    private static final Pattern TYPE_KEYWORD = Pattern.compile("\\b(class|interface|enum|record)\\b");

    private static final Pattern CONTROL_KEYWORD = Pattern.compile("^(if|for|while|switch|catch|synchronized|try|do|else|finally|return|new|->)\\b");

    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\.\\w+)*(\\([^()]*\\))?");

    private static final Pattern ANONYMOUS = Pattern.compile("\\bnew\\s+[\\w.<>?, ]+\\([^()]*(\\([^()]*\\)[^()]*)*\\)$");

    private static final Pattern METHOD_END = Pattern.compile("\\)\\s*(throws\\s+[\\w.,\\s<>]+)?$");

    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\([^()]*(\\([^()]*\\)[^()]*)*\\)\\s*(throws\\s+[\\w.,\\s<>]+)?$");

    private JavaSizeScanner() {
    }

    /** 루트 아래 모든 파일에서 한도를 넘은 곳을 모은다. */
    static List<Offender> scan(Path root) throws IOException {
        List<Offender> found = new ArrayList<>();
        for (Map.Entry<String, Path> file : JavaSourceScan.javaFiles(root).entrySet()) {
            found.addAll(scanFile(file.getKey(), Files.readString(file.getValue())));
        }
        return found;
    }

    /** 한 파일의 위반 — 파일 줄 수가 200 을 넘으면 {@code *} 한 건, 본문이 20 줄을 넘는 메서드마다 한 건. */
    static List<Offender> scanFile(String path, String source) {
        List<Offender> found = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        if (total > CLASS_LIMIT) {
            found.add(new Offender(path, "*", total));
        }
        methods(blankOut(source), (name, open, close) -> {
            int body = close - open - 1;
            if (body > METHOD_LIMIT) {
                found.add(new Offender(path, name, body));
            }
        });
        return found;
    }

    private interface MethodSink {
        void accept(String name, int openLine, int closeLine);
    }

    /** 문자열 리터럴 · 주석 · 텍스트 블록의 내용을 공백으로 바꾼다 — 줄바꿈은 남겨 줄 번호를 지킨다. */
    static String blankOut(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = blankRange(src, out, i, end < 0 ? n : end + 3);
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                i = blankLiteral(src, out, i);
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = blankRange(src, out, i, end < 0 ? n : end);
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = blankRange(src, out, i, end < 0 ? n : end + 2);
            } else {
                out.append(src.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int blankRange(String src, StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) {
            out.append(src.charAt(k) == '\n' ? '\n' : ' ');
        }
        return to;
    }

    private static int blankLiteral(String src, StringBuilder out, int from) {
        char quote = src.charAt(from);
        int j = from + 1;
        while (j < src.length() && src.charAt(j) != quote) {
            j += src.charAt(j) == '\\' ? 2 : 1;
        }
        int end = Math.min(j, src.length());
        out.append(quote).append(" ".repeat(Math.max(0, end - from - 1))).append(quote);
        return end + 1;
    }

    private static void methods(String code, MethodSink sink) {
        Deque<Object> stack = new ArrayDeque<>();
        int headerStart = 0;
        int line = 1;
        for (int idx = 0; idx < code.length(); idx++) {
            char ch = code.charAt(idx);
            if (ch == '\n') {
                line++;
            } else if (ch == ';') {
                headerStart = idx + 1;
            } else if (ch == '{') {
                stack.push(blockKind(code.substring(headerStart, idx), stack.isEmpty() || stack.peek() == Kind.TYPE, line));
                headerStart = idx + 1;
            } else if (ch == '}') {
                Object top = stack.isEmpty() ? Kind.OTHER : stack.pop();
                if (top instanceof MethodStart method) {
                    sink.accept(method.name(), method.line(), line);
                }
                headerStart = idx + 1;
            }
        }
    }

    private enum Kind { TYPE, OTHER }

    private record MethodStart(String name, int line) {
    }

    private static Object blockKind(String header, boolean inType, int line) {
        String flat = String.join(" ", header.trim().split("\\s+"));
        String h = ANNOTATION.matcher(flat).replaceAll(" ").trim();
        boolean typeWord = TYPE_KEYWORD.matcher(h).find();
        if (typeWord && !h.endsWith(")") || typeWord && (" " + h).contains(" record ")) {
            return Kind.TYPE;
        }
        if (ANONYMOUS.matcher(h).find()) {
            return Kind.TYPE;
        }
        if (inType && METHOD_END.matcher(h).find() && !CONTROL_KEYWORD.matcher(h).find()) {
            Matcher name = METHOD_NAME.matcher(h);
            return new MethodStart(name.find() ? name.group(1) : "?", line);
        }
        if (inType && (h.isEmpty() || h.equals("static"))) {
            return Kind.OTHER;
        }
        return inType && typeWord ? Kind.TYPE : Kind.OTHER;
    }
}
