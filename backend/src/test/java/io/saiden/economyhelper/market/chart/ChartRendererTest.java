package io.saiden.economyhelper.market.chart;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 그림을 <b>성질로</b> 검증한다 — 골든이 못 보는 자리라 여기가 유일한 그물이다.
 *
 * <p><b>픽셀을 하나하나 못 박지 않는다.</b> 안티앨리어싱은 JDK 판이 바뀌면 흔들리므로 그런
 * 단언은 「고침이 아니라 잡음」에 깨진다. 대신 <b>바뀌면 반드시 뜻이 바뀌는 것</b>만 본다 —
 * PNG인가, 크기가 맞나, 오른 날과 내린 날의 색이 다른가, 선이 위쪽에 그려지나.
 *
 * <p>⚠️ <b>해시만 비교하는 단언은 만들지 않는다.</b> 그건 무엇이 바뀌었는지 못 알려주므로
 * 저장소가 「커버리지 숫자만 올리고 아무것도 못 막는다」고 지우라 한 부류다.
 */
class ChartRendererTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

    private static List<DailyBar> bars(double... closes) {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            bars.add(new DailyBar(DAY.plusDays(i), BigDecimal.valueOf(closes[i])));
        }
        return bars;
    }

    private static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            throw new AssertionError("PNG를 읽을 수 없다", e);
        }
    }

    @Test
    @DisplayName("PNG가 나온다 — 매직 바이트와 크기로 확인한다")
    void producesAPng() {
        byte[] png = ChartRenderer.render(bars(100, 110, 105, 120));

        assertThat(png).isNotEmpty();
        // PNG 서명: 89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("PNG");

        BufferedImage image = decode(png);
        assertThat(image.getWidth()).isEqualTo(640);
        assertThat(image.getHeight()).isEqualTo(240);
    }

    @Test
    @DisplayName("점이 하나면 빈 배열 — 선이 없으므로 사진을 보내지 않는다")
    void refusesToDrawASinglePoint() {
        assertThat(ChartRenderer.render(bars(100))).isEmpty();
        assertThat(ChartRenderer.render(List.of())).isEmpty();
        assertThat(ChartRenderer.render(null)).isEmpty();
    }

    @Test
    @DisplayName("오른 날은 붉고 내린 날은 푸르다 — 본문의 🔴/🔵와 같은 방향이다")
    void colorsRisingAndFallingDifferently() {
        // 색을 RGB 리터럴로 못 박지 않는다. 안티앨리어싱과 면 그라데이션이 같은 색을 수십
        // 단계로 흩어 놓으므로 「가장 흔한 색 하나」도 못 박을 수 없다. 대신 **기울기**를 본다:
        // 판이 아닌 화소 전체에서 붉은 기가 우세한가 푸른 기가 우세한가.
        // 이것이 「등락률 이모지와 같은 방향」이라는 주장 그대로다 — 예전 단언(둘이 다르다)은
        // 상승과 하락을 뒤바꿔도 그대로 초록이었다
        assertThat(redOverBlue(ChartRenderer.render(bars(100, 200))))
                .as("오른 날이 붉지 않다").isPositive();
        assertThat(redOverBlue(ChartRenderer.render(bars(200, 100))))
                .as("내린 날이 푸르지 않다").isNegative();

        assertThat(distinctColors(ChartRenderer.render(bars(100, 200))))
                .as("선과 판이 함께 보여야 한다").isGreaterThan(1);
    }

    @Test
    @DisplayName("높은 값이 위에 그려진다 — 좌표가 뒤집히면 차트가 거꾸로 나간다")
    void drawsHigherValuesHigher() {
        // 화면 좌표는 아래로 갈수록 커지므로 뒤집기 실수가 나기 쉽다.
        // 단조 증가 계열이면 선의 무게중심이 오른쪽에서 위(=y가 작다)에 있어야 한다
        BufferedImage image = decode(ChartRenderer.render(bars(10, 20, 30, 40, 50)));

        int leftY = averageLineY(image, 40);
        int rightY = averageLineY(image, image.getWidth() - 40);

        assertThat(rightY)
                .as("오르는 계열인데 오른쪽 선이 왼쪽보다 아래에 있다 — y축이 뒤집혔다")
                .isLessThan(leftY);
    }

    @Test
    @DisplayName("열나흘 내내 같은 값이어도 터지지 않는다 — 스테이블코인이 실제로 그렇다")
    void survivesAFlatSeries() {
        byte[] png = ChartRenderer.render(bars(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));

        assertThat(png).as("최고와 최저가 같으면 나누기가 0이 된다").isNotEmpty();
        assertThat(decode(png).getHeight()).isEqualTo(240);
    }

    @Test
    @DisplayName("열나흘을 그려도 가볍다 — 사진이 무거우면 발송이 느려진다")
    void staysSmall() {
        byte[] smooth = ChartRenderer.render(
                bars(100, 102, 99, 105, 110, 108, 115, 120, 118, 125, 130, 128, 135, 140));
        // ⚠️ **매끈한 계열로만 재면 안 된다.** 진짜 환율은 톱니처럼 오르내리는데, 그 모양이
        //    면 그라데이션·세로선과 겹쳐 고유 색이 늘어 PNG 압축이 훨씬 덜 먹는다 —
        //    매끈한 것만 재고 통과시키면 실물에서 두 배가 나온다
        byte[] zigzag = ChartRenderer.render(
                bars(1412.20, 1398.50, 1405.80, 1401.17, 1410.40, 1395.10, 1408.90,
                        1399.60, 1412.50, 1403.30, 1397.80, 1409.10, 1400.20, 1406.60));

        // 실측 2026-09-08(세로선·점·고리를 더한 뒤): 매끈 13KB · 지그재그 27KB.
        // 상한은 「텔레그램에 사진 한 장」 기준으로 넉넉히 둔다 — 27KB는 사진으로 가벼운 쪽이다
        assertThat(smooth.length).as("매끈한 계열 실측 13KB").isLessThan(40_000);
        assertThat(zigzag.length).as("지그재그 계열 실측 27KB — 이쪽이 실물에 가깝다")
                .isLessThan(40_000);
    }

    @Test
    @DisplayName("세로선이 거래일과 맞는다 — 임의로 6등분하면 있지도 않은 날 경계를 그린다")
    void verticalLinesLandOnTradingDays() {
        // ⚠️ 예전에는 x축을 COLUMNS=6으로 나눠 일곱 줄을 그었다. 자료점은 열넷(간격 열셋)이라
        //    양 끝 말고는 **어느 세로선도 거래일과 맞지 않았다** — 그런데 그림은 세로선을
        //    하루로 읽으라고 유도한다. 그림에 있는 선은 자료에 있는 것이어야 한다
        List<DailyBar> bars = bars(100, 102, 99, 105, 110, 108, 115, 120, 118, 125, 130, 128, 135, 140);
        BufferedImage image = decode(ChartRenderer.render(bars));

        // ⚠️ 격자색과 **정확히** 같은 화소를 세지 않는다 — 1px 선이 안티앨리어싱되면 두 열에
        //    반씩 걸려 어느 화소도 그 색이 아니게 된다. 「배경이 아닌 것」의 세로 길이로 본다
        int between = (xOf(0, bars.size()) + xOf(1, bars.size())) / 2;
        int onBetween = paintedPixels(image, between);
        for (int i = 0; i < bars.size(); i++) {
            int x = xOf(i, bars.size());
            assertThat(paintedPixels(image, x))
                    .as("%d번째 거래일(x=%d)에 세로선이 없다", i, x)
                    .isGreaterThan(onBetween + 100);
        }

        // 자료점 사이 한가운데에는 세로선이 없어야 한다 — 있으면 그것은 자료가 아니다
        assertThat(onBetween)
                .as("자료점이 아닌 x=%d에 세로선이 있다 — 없는 날을 그렸다", between)
                .isLessThan(60);
    }

    @Test
    @DisplayName("날마다 점을 찍는다 — 꺾인 자리가 표본인지 보간인지 보여야 한다")
    void marksEveryTradingDay() {
        List<DailyBar> bars = bars(100, 102, 99, 105, 110, 108, 115, 120, 118, 125, 130, 128, 135, 140);
        BufferedImage image = decode(ChartRenderer.render(bars));

        // 점이 있으면 그 열의 선 색 화소가 이웃 중간 열보다 많다. 가운데 자료점 하나로 본다
        // (양 끝은 끝점 강조·여백 때문에 뜻이 섞인다)
        int middle = bars.size() / 2;
        int onPoint = drawnPixels(image, xOf(middle, bars.size()));
        int offPoint = drawnPixels(image,
                (xOf(middle, bars.size()) + xOf(middle + 1, bars.size())) / 2);

        // 실측: 점이 있으면 6, 그 사이는 3이다. 점을 지우면 꼭짓점이 굵어지는 만큼(+1 안팎)만
        // 남으므로 「크다」만으로는 약하다 — 둘 이상 차이를 요구한다
        assertThat(onPoint).as("표본 자리(%d)가 그 사이(%d)보다 뚜렷이 굵어야 점이 있는 것이다",
                        onPoint, offPoint)
                .isGreaterThanOrEqualTo(offPoint + 2);
    }

    @Test
    @DisplayName("기간 고가·저가에 고리를 얹는다 — 극값이 어느 날이었는지 짚는다")
    void ringsTheWindowHighAndLow() {
        // 최고는 마지막(140), 최저는 세 번째(99)
        List<DailyBar> bars = bars(100, 102, 99, 105, 110, 108, 115, 120, 118, 125, 130, 128, 135, 140);
        BufferedImage image = decode(ChartRenderer.render(bars));

        int lowIndex = 2;
        int onLow = drawnPixels(image, xOf(lowIndex, bars.size()));
        int onPlain = drawnPixels(image, xOf(1, bars.size()));

        // 실측: 고리가 얹힌 저가 자리가 12, 평범한 날이 6이다
        assertThat(onLow).as("저가 자리(%d)는 고리가 얹혀 평범한 날(%d)보다 굵어야 한다",
                        onLow, onPlain)
                .isGreaterThanOrEqualTo(onPlain + 4);
    }

    @Test
    @DisplayName("같은 값이 두 날이면 고리도 둘 — 응답 순서가 화면을 정하면 안 된다")
    void ringsEveryTiedExtreme() {
        // 최저 99가 둘째와 여섯째에 있다. 「먼저 온 것 하나」만 찍으면 뒤엣것이 빈다
        List<DailyBar> bars = bars(100, 99, 105, 110, 108, 99, 115, 120, 118, 125, 130, 128, 135, 140);
        BufferedImage image = decode(ChartRenderer.render(bars));

        int plain = drawnPixels(image, xOf(2, bars.size()));

        assertThat(drawnPixels(image, xOf(1, bars.size())))
                .as("첫째 최저에 고리가 없다").isGreaterThan(plain);
        assertThat(drawnPixels(image, xOf(5, bars.size())))
                .as("둘째 최저에 고리가 없다 — 동점을 순서로 가른 것이다").isGreaterThan(plain);
    }

    @Test
    @DisplayName("평평한 계열에는 고가·저가 고리가 없다 — 다 같으면 극값이 없다")
    void drawsNoRingsWhenNothingMoved() {
        List<DailyBar> flat = bars(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        BufferedImage image = decode(ChartRenderer.render(flat));

        // 고리(반지름 5.5)가 얹히면 그 열의 그려진 화소가 선+점(둘 다 y=120 근처)보다 뚜렷이 는다
        int drawn = drawnPixels(image, xOf(3, flat.size()));

        // 실측: 선+점만이면 4다. 고리가 얹히면 오르는 계열에서처럼 6쯤 늘어 10을 넘는다
        assertThat(drawn)
                .as("어느 한 점을 「고가」라 부르면 거짓이다 — 고리는 안 얹는다. 실측 %d", drawn)
                .isLessThanOrEqualTo(8);
    }

    /** {@code ChartRenderer.xOf}와 같은 셈 — 자료점의 x. 렌더러의 것은 private이라 여기서 다시 센다. */
    private static int xOf(int index, int count) {
        int pad = 18;
        int width = 640;
        return pad + (int) Math.round((double) index / (count - 1) * (width - 2 * pad));
    }

    /** 그 세로줄에서 배경이 아닌 화소 수 — <b>세로 격자선이 거기 있나</b>를 본다(격자색과 정확히 같지 않아도 잡힌다). */
    private static int paintedPixels(BufferedImage image, int x) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(x, y) & 0xFFFFFF) != (ChartRenderer.BACKGROUND.getRGB() & 0xFFFFFF)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 그 세로줄에서 <b>우리가 그린 것</b>의 화소 수 — 선·점·고리다.
     *
     * <p>⚠️ <b>「판이 아닌 것」으로 세면 안 된다.</b> 세로 격자선이 안티앨리어싱되면 격자색과
     * 정확히 같지 않아 판에서 빠지고, 그 200여 화소가 점·고리 신호를 통째로 삼킨다 —
     * 처음에 그렇게 써서 <b>테스트 둘이 엉뚱한 이유로 통과했다.</b> 그래서 색으로 가른다:
     * 오름·내림은 붉거나 푸르고(채널 차), 평평은 밝은 회색이다(기준선보다 밝다).
     */
    private static int drawnPixels(BufferedImage image, int x) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            int rgb = image.getRGB(x, y) & 0xFFFFFF;
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            boolean hued = Math.abs(red - blue) > 40;
            boolean brighterThanBaseline = red + green + blue > 0x1A0;
            if (hued || brighterThanBaseline) {
                count++;
            }
        }
        return count;
    }

    /**
     * 판에 속한 색인가 — 배경·격자·기준선.
     *
     * <p><b>값을 여기 리터럴로 적지 않는다.</b> 예전에는 흰 배경 {@code 0xFFFFFF}와 회색
     * 기준선 {@code 0xC8C8C8}이 이 파일에 박혀 있었는데, 렌더러의 판 색을 바꾸는 순간
     * 그 둘이 아무것도 못 걸러 <b>판 전체가 「선」으로 세어졌다</b> — 단언은 그대로 초록인데
     * 보는 것이 없어진다. 그래서 렌더러가 든 상수를 그대로 든다.
     */
    private static boolean board(int rgb) {
        return rgb == (ChartRenderer.BACKGROUND.getRGB() & 0xFFFFFF)
                || rgb == (ChartRenderer.GRID.getRGB() & 0xFFFFFF)
                || rgb == (ChartRenderer.BASELINE.getRGB() & 0xFFFFFF);
    }

    /** 그 세로줄에서 판이 아닌 화소들의 평균 y — 선의 위치를 대신한다. */
    private static int averageLineY(BufferedImage image, int x) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if (!board(image.getRGB(x, y) & 0xFFFFFF)) {
                sum += y;
                count++;
            }
        }
        if (count == 0) {
            throw new AssertionError("x=" + x + "에 선이 없다 — 그림이 비었나?");
        }
        return (int) (sum / count);
    }

    private static long distinctColors(byte[] png) {
        BufferedImage image = decode(png);
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int x = 0; x < image.getWidth(); x += 4) {
            for (int y = 0; y < image.getHeight(); y += 4) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    /**
     * 판이 아닌 화소들의 <b>붉은 기 − 푸른 기</b> 합.
     *
     * <p>양수면 붉고 음수면 푸르다. 화소 하나하나의 색이 아니라 그림 전체의 기울기를 보므로
     * 안티앨리어싱과 면 그라데이션이 값을 흩어 놓아도 부호는 흔들리지 않는다.
     */
    private static long redOverBlue(byte[] png) {
        BufferedImage image = decode(png);
        long sum = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (!board(rgb)) {
                    sum += ((rgb >> 16) & 0xFF) - (rgb & 0xFF);
                }
            }
        }
        return sum;
    }

    @Test
    @DisplayName("글자를 한 자도 그리지 않는다 — 배포 이미지에 폰트가 없어 위반이 두부로만 드러난다")
    void drawsNoText() {
        // ⚠️ 이 클래스가 스스로 「핵심 결정」이라 부르는 것인데 그물이 없었다. 어기면
        //    개발 기계에서는 멀쩡하고(폰트가 있다) 골든도 못 본다(그림은 텍스트가 아니다) —
        //    배포처(eclipse-temurin:21-jre)에서 □□□로만 드러난다. 즉 **테스트가 유일한 방어**다.
        // 픽셀로는 잴 수 없으니(글자도 결국 선이다) 클래스 파일이 무엇을 참조하는지 본다.
        byte[] bytecode = bytecodeOfRenderer();

        assertThat(bytecode)
                .as("클래스 파일을 못 읽었다 — 스캔이 깨지면 이 테스트는 아무것도 안 본다")
                .hasSizeGreaterThan(1000);

        String pool = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String forbidden : new String[] {
                "drawString", "drawChars", "drawBytes", "drawGlyphVector",
                "java/awt/Font", "java/awt/font/", "TextLayout"}) {
            assertThat(pool)
                    .as("ChartRenderer가 %s를 참조한다 — 폰트 없는 런타임에서 두부가 된다. "
                            + "낱말과 숫자는 caption에 둔다", forbidden)
                    .doesNotContain(forbidden);
        }

        // 그리는 것은 참조하고 있어야 한다 — 위 단언이 「아무것도 안 그린다」로 통과하면 안 된다
        assertThat(pool)
                .as("기하를 그리는 참조가 사라졌다 — 위 단언이 빈 클래스에도 통과하게 된다")
                .contains("drawLine");
    }

    /** 컴파일된 {@code ChartRenderer}의 바이트. 못 찾으면 빈 배열이고 위 단언이 잡는다. */
    private static byte[] bytecodeOfRenderer() {
        String resource = ChartRenderer.class.getName().replace('.', '/') + ".class";
        try (var stream = ChartRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            return stream == null ? new byte[0] : stream.readAllBytes();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }
}
