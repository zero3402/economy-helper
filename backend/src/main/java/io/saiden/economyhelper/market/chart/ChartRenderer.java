package io.saiden.economyhelper.market.chart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 일봉을 선 하나로 굽는다 — <b>글자를 넣지 않는다.</b>
 *
 * <p><b>새 의존성이 없다.</b> JDK의 {@code Graphics2D}+{@code ImageIO}로 PNG가 나온다
 * (실측: 320×120 선 그래프가 3,766바이트, {@code java.awt.headless=true}).
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
 * 위에 있으면 올랐고 아래면 내렸다. 눈금 숫자가 없어도 방향은 읽힌다.
 *
 * <p>I/O를 모르는 순수 계산이다({@code PrecipitationSpells}와 같은 자리라 스프링 없이 테스트한다).
 */
public final class ChartRenderer {

    /** 텔레그램이 사진을 폭에 맞춰 늘리므로 너무 작으면 흐려진다. 실측 3~4KB로 가볍다. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 240;

    /** 선이 테두리에 닿지 않게 둘레를 비운다. */
    private static final int PAD = 16;

    private static final Color BACKGROUND = new Color(0xFF, 0xFF, 0xFF);
    private static final Color BASELINE = new Color(0xC8, 0xC8, 0xC8);

    /** 오른 것은 붉게, 내린 것은 푸르게 — 등락률 이모지(🔴/🔵)와 같은 방향이다. */
    private static final Color UP = new Color(0xD3, 0x2F, 0x2F);
    private static final Color DOWN = new Color(0x15, 0x65, 0xC0);
    private static final Color FLAT = new Color(0x61, 0x61, 0x61);

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
            canvas.setColor(BACKGROUND);
            canvas.fillRect(0, 0, WIDTH, HEIGHT);

            double low = bars.stream().mapToDouble(bar -> bar.close().doubleValue()).min().orElse(0);
            double high = bars.stream().mapToDouble(bar -> bar.close().doubleValue()).max().orElse(0);
            double first = bars.get(0).close().doubleValue();
            double last = bars.get(bars.size() - 1).close().doubleValue();

            // 첫날 값에 가로 기준선 — 선이 위면 올랐고 아래면 내렸다. 숫자 없이 방향을 읽는 장치다
            canvas.setColor(BASELINE);
            canvas.setStroke(new BasicStroke(1f));
            int baseline = yOf(first, low, high);
            canvas.drawLine(PAD, baseline, WIDTH - PAD, baseline);

            canvas.setColor(colorOf(first, last));
            canvas.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < bars.size(); i++) {
                canvas.drawLine(
                        xOf(i - 1, bars.size()), yOf(bars.get(i - 1).close().doubleValue(), low, high),
                        xOf(i, bars.size()), yOf(bars.get(i).close().doubleValue(), low, high));
            }
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

    /** 그림이 오를 방향인가 — 테스트가 기하를 확인하는 데 쓴다. */
    static boolean rising(BigDecimal first, BigDecimal last) {
        return last.compareTo(first) > 0;
    }
}
