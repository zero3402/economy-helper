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
    @DisplayName("오른 날과 내린 날의 선 색이 다르다 — 등락률 이모지와 같은 방향이다")
    void colorsRisingAndFallingDifferently() {
        // 색을 RGB 리터럴로 못 박지 않는다. 「둘이 다르다」가 주장이고, 그것만 본다
        long rising = distinctColors(ChartRenderer.render(bars(100, 200)));
        long falling = distinctColors(ChartRenderer.render(bars(200, 100)));

        assertThat(rising).as("선과 배경과 기준선이 보여야 한다").isGreaterThan(1);
        assertThat(dominantLineColor(ChartRenderer.render(bars(100, 200))))
                .as("오른 날과 내린 날이 같은 색이면 방향을 색으로 읽을 수 없다")
                .isNotEqualTo(dominantLineColor(ChartRenderer.render(bars(200, 100))));
        assertThat(falling).isGreaterThan(1);
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
        byte[] png = ChartRenderer.render(
                bars(100, 102, 99, 105, 110, 108, 115, 120, 118, 125, 130, 128, 135, 140));

        assertThat(png.length).as("실측 3~4KB 급이어야 한다").isLessThan(30_000);
    }

    /** 그 세로줄에서 배경도 기준선도 아닌 화소들의 평균 y — 선의 위치를 대신한다. */
    private static int averageLineY(BufferedImage image, int x) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            int rgb = image.getRGB(x, y) & 0xFFFFFF;
            if (rgb != 0xFFFFFF && rgb != 0xC8C8C8) {
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

    /** 배경·기준선을 뺀 가장 흔한 색 — 선의 색이다. */
    private static int dominantLineColor(byte[] png) {
        BufferedImage image = decode(png);
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb != 0xFFFFFF && rgb != 0xC8C8C8) {
                    counts.merge(rgb, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElseThrow(() -> new AssertionError("선이 없다"));
    }
}
