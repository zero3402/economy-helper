package io.saiden.economyhelper.market.chart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 일봉을 선 하나로 굽는다 — <b>글자를 넣지 않는다.</b>
 *
 * <p><b>새 의존성이 없다.</b> JDK의 {@code Graphics2D}+{@code ImageIO}로 PNG가 나온다.
 * 격자·면 채우기·끝점도 전부 표준 {@code java.awt}다({@link GeneralPath}·{@link GradientPaint}·
 * {@link Ellipse2D}, 점선은 {@link BasicStroke}의 dash).
 *
 * <p>⚠️ <b>글자를 넣지 않는 것이 이 클래스의 핵심 결정이다.</b> 런타임 이미지가
 * {@code eclipse-temurin:21-jre}이고 <b>폰트 패키지를 안 깐다</b> — 글자를 그리면 배포처에서
 * 두부(□□□)가 될 수 있다. 개발 기계에 폰트가 있다는 것은 아무 증거가 아니다.
 * 그래서 <b>낱말과 숫자는 전부 caption</b>에 둔다. 얻는 것이 둘 더 있다:
 * caption이 텍스트이므로 <b>골든이 낱말 전부를 계속 덮고</b>, 그림은 결정적 기하라서
 * 성질로 검증할 수 있다.
 *
 * <p>폰트를 깔아서 해결하지 않는다 — 이미지가 커지고, 「배포처에 폰트가 있다」는 전제가
 * 문서 어디에도 없는 새 숨은 의존이 된다.
 *
 * <p><b>축도 숫자 없이 뜻을 낸다.</b> 가로 기준선 하나를 <b>첫날 값</b>에 그린다 — 선이 그
 * 위에 있으면 올랐고 아래면 내렸다. 눈금 숫자가 없어도 방향은 읽힌다. <b>기준선만 점선</b>인
 * 것은 격자와 뜻이 다르기 때문이다 — 격자는 눈이 기대는 자이고 기준선은 값이다.
 *
 * <p><b>바탕이 어둡다.</b> 트레이딩뷰 기본 화면과 같은 자리인데, 고른 이유는 흉내가 아니라
 * <b>텔레그램에 실리는 방식</b>이다: 사진은 말풍선 안에 통째로 박히므로 흰 판이면 다크 모드
 * 대화에서 홀로 빛나는 사각형이 된다. 어두운 판은 두 테마 어디에 놓아도 튀지 않는다.
 *
 * <p>⚠️ <b>선 색은 한국식 그대로다</b> — 오르면 붉고 내리면 푸르다. 트레이딩뷰 기본색
 * (상승 청록·하락 빨강)으로 바꾸면 그림은 그쪽다워지지만 <b>바로 위 본문의 {@code 🔴 +1.00%}와
 * 방향이 뒤집힌다</b>. 한 통 안에서 색이 두 뜻을 갖게 되는 쪽이 더 나쁘다.
 *
 * <p>I/O를 모르는 순수 계산이다({@code HalfDays}와 같은 자리라 스프링 없이 테스트한다).
 */
public final class ChartRenderer {

    static {
        // ImageIO는 기본값으로 쓰기마다 java.io.tmpdir에 임시 파일을 만든다(FileCacheImageOutputStream).
        // 640×240 PNG를 메모리 스트림에 쓰는 데 디스크를 거칠 이유가 없다 — 브리핑 한 번에 열 장이다
        ImageIO.setUseCache(false);
    }

    /** 텔레그램이 사진을 폭에 맞춰 늘리므로 너무 작으면 흐려진다. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 240;

    /** 선이 테두리에 닿지 않게 둘레를 비운다. 격자의 바깥 테두리가 곧 이 안쪽 경계다. */
    private static final int PAD = 18;

    /**
     * 판 색 — <b>테스트가 이 이름으로 읽는다.</b>
     *
     * <p>{@code ChartRendererTest}가 「선이 아닌 화소」를 골라낼 때 배경·격자·기준선을 빼야
     * 하는데, 그것을 테스트에 리터럴로 적어 두면 여기 색을 바꾸는 날 <b>테스트가 조용히
     * 아무것도 안 보게 된다</b>(전부 「선」으로 세어져 단언이 뜻을 잃는다). 그래서 값을 한 곳에
     * 두고 테스트가 그것을 든다.
     */
    static final Color BACKGROUND = new Color(0x13, 0x17, 0x22);
    static final Color GRID = new Color(0x2A, 0x2E, 0x39);
    static final Color BASELINE = new Color(0x78, 0x7B, 0x86);

    /** 오른 것은 붉게, 내린 것은 푸르게 — 등락률 이모지(🔴/🔵)와 같은 방향이다. */
    static final Color UP = new Color(0xE5, 0x39, 0x35);
    static final Color DOWN = new Color(0x42, 0x8E, 0xFF);
    static final Color FLAT = new Color(0x9A, 0xA0, 0xAE);

    /** 격자 칸 수. 가로가 넓으므로 세로선을 더 촘촘히 둔다. */
    private static final int ROWS = 4;
    private static final int COLUMNS = 6;

    private ChartRenderer() {
    }

    /**
     * @param bars 날짜 순으로 정렬된 일봉. {@link DailySeries#recent}가 만든 것을 받는다
     * @return PNG 바이트. 점이 둘 미만이면 <b>빈 배열</b> — 선이 없으므로 그릴 것이 없다
     */
    public static byte[] render(List<DailyBar> bars) {
        if (!DailySeries.drawable(bars)) {
            // 그림을 안 그리는 것과 빈 그림을 그리는 것은 다르다 — 부르는 쪽이 사진을 안 보낸다
            return new byte[0];
        }

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            canvas.setColor(BACKGROUND);
            canvas.fillRect(0, 0, WIDTH, HEIGHT);

            double low = bars.stream().mapToDouble(bar -> bar.close().doubleValue()).min().orElse(0);
            double high = bars.stream().mapToDouble(bar -> bar.close().doubleValue()).max().orElse(0);
            double first = bars.get(0).close().doubleValue();
            double last = bars.get(bars.size() - 1).close().doubleValue();
            Color line = colorOf(first, last);

            grid(canvas);
            baseline(canvas, yOf(first, low, high));
            area(canvas, bars, low, high, line);
            polyline(canvas, bars, low, high, line);
            endpoint(canvas, xOf(bars.size() - 1, bars.size()), yOf(last, low, high), line);
        } finally {
            canvas.dispose();
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", png)) {
                throw new IllegalStateException("PNG 인코더가 없습니다");
            }
        } catch (IOException e) {
            // ByteArrayOutputStream에 쓰다 나는 IOException은 사실상 없다 — 검사 예외만 걷어낸다
            throw new UncheckedIOException(e);
        }
        return png.toByteArray();
    }

    /**
     * 눈이 기댈 자 — <b>숫자가 없어도 기울기를 읽게 해 준다.</b>
     *
     * <p>선 하나만 떠 있으면 얼마나 가파른지 견줄 것이 없다. 격자가 그 자를 대신한다.
     * 값과는 무관하므로(눈금이 아니다) 칸 수를 고정해 둔다 — 값에 맞춰 움직이면 눈금처럼
     * 보이는데, 숫자가 없으니 <b>읽을 수 없는 눈금</b>이 된다.
     */
    private static void grid(Graphics2D canvas) {
        canvas.setColor(GRID);
        canvas.setStroke(new BasicStroke(1f));
        for (int row = 0; row <= ROWS; row++) {
            int y = PAD + Math.round((float) row / ROWS * (HEIGHT - 2 * PAD));
            canvas.drawLine(PAD, y, WIDTH - PAD, y);
        }
        for (int column = 0; column <= COLUMNS; column++) {
            int x = PAD + Math.round((float) column / COLUMNS * (WIDTH - 2 * PAD));
            canvas.drawLine(x, PAD, x, HEIGHT - PAD);
        }
    }

    /** 첫날 값 — 선이 위면 올랐고 아래면 내렸다. 격자와 갈리도록 점선이다. */
    private static void baseline(Graphics2D canvas, int y) {
        canvas.setColor(BASELINE);
        canvas.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[] {4f, 4f}, 0f));
        canvas.drawLine(PAD, y, WIDTH - PAD, y);
    }

    /**
     * 선 아래를 옅게 채운다 — <b>선이 어느 쪽 면인지 알려 준다.</b>
     *
     * <p>배경 쪽으로 사라지는 그라데이션이라 아래쪽 경계가 눈에 잡히지 않는다. 단색으로
     * 채우면 「선 아래」가 또 하나의 덩어리가 되어 선보다 먼저 읽힌다.
     */
    private static void area(Graphics2D canvas, List<DailyBar> bars,
                             double low, double high, Color line) {
        int bottom = HEIGHT - PAD;
        GeneralPath path = new GeneralPath();
        path.moveTo(xOf(0, bars.size()), bottom);
        for (int i = 0; i < bars.size(); i++) {
            path.lineTo(xOf(i, bars.size()), yOf(bars.get(i).close().doubleValue(), low, high));
        }
        path.lineTo(xOf(bars.size() - 1, bars.size()), bottom);
        path.closePath();

        canvas.setPaint(new GradientPaint(
                0, PAD, new Color(line.getRed(), line.getGreen(), line.getBlue(), 0x55),
                0, bottom, new Color(line.getRed(), line.getGreen(), line.getBlue(), 0)));
        canvas.fill(path);
    }

    private static void polyline(Graphics2D canvas, List<DailyBar> bars,
                                 double low, double high, Color line) {
        canvas.setColor(line);
        canvas.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < bars.size(); i++) {
            canvas.drawLine(
                    xOf(i - 1, bars.size()), yOf(bars.get(i - 1).close().doubleValue(), low, high),
                    xOf(i, bars.size()), yOf(bars.get(i).close().doubleValue(), low, high));
        }
    }

    /**
     * 마지막 값에 점 하나 — <b>「지금」이 어디인지 짚는다.</b>
     *
     * <p>선의 오른쪽 끝이 곧 현재값인데, 끝이 오른쪽 여백에 닿아 있으면 잘린 것처럼 보인다.
     * 후광을 함께 두는 것은 선 색과 같은 점이 선 위에 얹히면 안 보이기 때문이다.
     */
    private static void endpoint(Graphics2D canvas, int x, int y, Color line) {
        canvas.setPaint(new Color(line.getRed(), line.getGreen(), line.getBlue(), 0x44));
        canvas.fill(new Ellipse2D.Double(x - 7.0, y - 7.0, 14.0, 14.0));
        canvas.setPaint(line);
        canvas.fill(new Ellipse2D.Double(x - 3.5, y - 3.5, 7.0, 7.0));
    }

    private static Color colorOf(double first, double last) {
        int direction = Double.compare(last, first);
        return direction > 0 ? UP : direction < 0 ? DOWN : FLAT;
    }

    private static int xOf(int index, int count) {
        return PAD + (int) Math.round((double) index / (count - 1) * (WIDTH - 2 * PAD));
    }

    /**
     * 값 하나를 세로 좌표로.
     *
     * <p><b>값의 폭이 0이면 가운데에 그린다.</b> 열나흘 내내 같은 값이면 최고와 최저가 같아
     * 나누기가 터진다 — 드문 일이지만 코인의 스테이블코인에서는 실제로 난다.
     */
    private static int yOf(double value, double low, double high) {
        double span = high - low;
        if (span <= 0) {
            return HEIGHT / 2;
        }
        double ratio = (value - low) / span;
        // 화면 좌표는 아래로 갈수록 커진다 — 높은 값이 위에 와야 한다
        return HEIGHT - PAD - (int) Math.round(ratio * (HEIGHT - 2 * PAD));
    }
}
