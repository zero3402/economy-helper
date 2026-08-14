import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 주식·지수 타일을 그린다. 빌드에 들어가지 않는 <b>일회성 도구</b>다.
 *
 * <pre>
 * cd backend/tools && java LogoTiles.java
 * </pre>
 *
 * <p><b>왜 진짜 로고가 아닌가.</b> 기업 로고는 상표다. 화면에 띄워 "이 회사 주가"를 가리키는
 * 것과 파일을 저장소에 넣어 배포하는 것은 다른 행위다. 코인은 {@code cryptocurrency-icons}가
 * CC0(퍼블릭 도메인)로 재도안 아이콘을 배포해 그대로 썼지만, 기업 로고에는 그런 세트가 없다.
 * 위키미디어의 {@code PD-textlogo}도 <b>저작권만</b> 소멸했을 뿐 상표는 그대로다.
 *
 * <p>그래서 <b>글자만</b> 넣는다. 도형·심벌을 흉내 내지 않는다 — 흉내 내는 순간
 * "그 회사 로고 비슷한 것"이 되어 애초에 피하려던 문제로 돌아간다.
 *
 * <p><b>색은 키에서 유도한다.</b> 손으로 고르면 스물다섯 개를 관리해야 하고, 종목을 더할 때마다
 * 안 겹치는 색을 찾아야 한다. 해시로 색상환을 돌면 같은 종목은 언제나 같은 색이고 새 종목이
 * 기존 색을 흔들지 않는다.
 *
 * <p>한글은 <b>이 프로그램을 돌리는 시점</b>에 그려지고 커밋되는 건 픽셀이라, 배포 환경에
 * 한글 폰트가 없어도 상관없다.
 *
 * <p>종목을 더하려면 아래 목록에 한 줄 넣고 다시 돌린다. 코드는 고칠 것이 없다.
 */
public final class LogoTiles {

    private static final int SIZE = 128;
    private static final File OUT = new File("../src/main/resources/logo");

    /**
     * @param file 저장할 이름. {@code LogoCatalog}가 찾는 키와 같아야 한다 —
     *             국내 종목은 종목코드, 미국은 소문자 티커, 지수는 별칭이다
     * @param text 타일에 넣을 글자. {@code |}로 줄을 나눈다
     */
    record Tile(String file, String text) {}

    private static final List<Tile> TILES = List.of(
            // 국내 시총 상위 10 — 파일명이 종목코드인 이유는 StockQuote.code()가 그것이기 때문이다
            new Tile("005930", "삼성|전자"),
            new Tile("000660", "SK|하이닉스"),
            new Tile("373220", "LG|에너지"),
            new Tile("207940", "삼성|바이오"),
            new Tile("005380", "현대차"),
            new Tile("000270", "기아"),
            new Tile("068270", "셀트리온"),
            new Tile("105560", "KB|금융"),
            new Tile("035420", "NAVER"),
            new Tile("055550", "신한|지주"),

            // 미국 시총 상위 10 — FMP가 주는 티커 그대로
            new Tile("nvda", "NVDA"),
            new Tile("aapl", "AAPL"),
            new Tile("msft", "MSFT"),
            new Tile("googl", "GOOGL"),
            new Tile("amzn", "AMZN"),
            new Tile("meta", "META"),
            new Tile("avgo", "AVGO"),
            new Tile("tsla", "TSLA"),
            new Tile("brk-b", "BRK.B"),
            new Tile("lly", "LLY"),

            // 지수. 국내는 종목코드가 없어 이름으로 찾고, 미국은 ^IXIC에서 ^를 뗀 형태다
            new Tile("kospi", "KOSPI"),
            new Tile("kosdaq", "KOSDAQ"),
            new Tile("ixic", "NASDAQ"),
            new Tile("gspc", "S&P|500"),
            new Tile("dji", "DOW"));

    public static void main(String[] args) throws Exception {
        if (!OUT.isDirectory()) {
            throw new IllegalStateException("출력 디렉터리가 없습니다: " + OUT.getCanonicalPath());
        }
        for (Tile tile : TILES) {
            ImageIO.write(draw(tile), "png", new File(OUT, tile.file() + ".png"));
        }
        System.out.println(TILES.size() + "장을 " + OUT.getCanonicalPath() + "에 썼습니다");
    }

    private static BufferedImage draw(Tile tile) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Color background = colorOf(tile.file());
        g.setColor(background);
        // 원이 아니라 둥근 사각형인 이유는 코인 아이콘(원)과 한눈에 갈리게 하려는 것이다
        g.fill(new RoundRectangle2D.Float(0, 0, SIZE, SIZE, 28, 28));
        g.setColor(new Color(255, 255, 255, 46));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Float(1, 1, SIZE - 2f, SIZE - 2f, 27, 27));

        String[] lines = tile.text().split("\\|");
        g.setColor(Color.WHITE);
        drawCentered(g, lines);

        g.dispose();
        return image;
    }

    /** 줄 수와 가장 긴 줄에 맞춰 글자 크기를 줄인다 — 삼성바이오로직스가 넘치지 않게. */
    private static void drawCentered(Graphics2D g, String[] lines) {
        int lineHeight = lines.length == 1 ? 56 : 40;
        for (int size = lineHeight; size >= 12; size--) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            FontRenderContext context = g.getFontRenderContext();
            double widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, font.getStringBounds(line, context).getWidth());
            }
            if (widest > SIZE - 20) {
                continue;
            }

            g.setFont(font);
            int step = (int) (size * 1.15);
            int top = SIZE / 2 - (step * (lines.length - 1)) / 2
                    + g.getFontMetrics().getAscent() / 2 - 2;
            for (int i = 0; i < lines.length; i++) {
                int width = (int) font.getStringBounds(lines[i], context).getWidth();
                g.drawString(lines[i], (SIZE - width) / 2, top + step * i);
            }
            return;
        }
    }

    /**
     * 키 → 색. 채도·명도는 고정하고 색상만 돌린다 — 밝기가 제각각이면 흰 글자가 어떤
     * 타일에서는 안 읽힌다.
     */
    private static Color colorOf(String key) {
        int hash = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash = hash * 31 + b;
        }
        float hue = Math.floorMod(hash, 360) / 360f;
        return Color.getHSBColor(hue, 0.62f, 0.62f);
    }
}
