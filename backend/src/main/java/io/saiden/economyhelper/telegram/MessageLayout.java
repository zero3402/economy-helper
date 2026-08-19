package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.FxRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <b>모든 통이 공유하는 뼈대와 표기 규칙.</b>
 *
 * <p>{@code ARCHITECTURE.md} 4-6이 "모든 통이 제목 / 값 / 출처 / 기준의 같은 뼈대를 쓴다"고
 * 적어 뒀는데, 그 뼈대가 도메인 다섯의 표현과 한 파일에 섞여 있었다. 규칙이 한 자리에 있어야
 * 다섯 통이 갈리지 않는다.
 *
 * <p><b>여기 있는 것의 기준은 하나다 — 호출자가 둘 이상인가.</b>
 * 제목·빈 결과·출처 줄·등락률·숫자 표기·원화 환산·날짜 형식이 그렇다.
 * 호출자가 하나뿐인 것({@code priceOf}·{@code convertible}·{@code unlinkable}·{@code premium})은
 * 공유 규칙이 아니므로 제 도메인에 남는다 — 여기 끌어오면 "공용"이라는 말이 값을 잃는다.
 *
 * <p><b>HTML 이스케이프는 여기서 한다</b>({@link #sources}). 이어붙인 뒤 통째로 하면 구분자까지
 * 대상이 되고, 호출부가 한 번 더 하면 {@code S&P 500}이 두 번 이스케이프된다.
 *
 * <p>텔레그램이 허용하는 태그는 부분집합이다({@code b·i·u·s·a·code·pre·blockquote}).
 * 굵게는 <b>제목에만</b> 쓴다 — 값까지 굵으면 무엇이 계층인지 드러나지 않는다.
 */
final class MessageLayout {

    private MessageLayout() {
    }

    /** 시세는 "언제 값인지"가 값 자체만큼 중요하다. 사용자는 한국에 있으므로 KST로 보여준다. */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 날짜에는 <b>언제나 요일을 붙인다</b> — {@code 2026년 8월 17일(월)}.
     *
     * <p>값이 언제 것인지를 사람이 실제로 판단하는 단위가 요일이다. "8월 15일 종가"만 보면
     * 그게 금요일 종가인지 주말에 멈춘 값인지 세어 봐야 알고, 일주일치 날씨에서 찾는 것도
     * 날짜가 아니라 "이번 주말"이다. 환율의 {@code (고시)}, 증시의 {@code (종가)}처럼
     * <b>값의 성격을 밝히는 일</b>의 연장이다.
     *
     * <p>⚠️ {@link Locale#KOREAN}을 명시하지 않으면 서버 로케일에 따라 {@code Mon}으로 나온다.
     */
    static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E) HH:mm:ss", Locale.KOREAN);

    static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E)", Locale.KOREAN);

    /**
     * 연도를 되풀이하지 않는 자리 — 범위의 끝({@code ~ 8월 24일(월)})과 날짜별 블록 제목.
     *
     * <p>둘이 같은 모양이라 상수도 하나다. 범위의 시작에 이미 연도가 적혀 있고, 날짜별 블록은
     * 맨 아래 기준 줄이 연도를 이고 있다.
     */
    static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);

    /**
     * <b>답을 만들다 예상 못 한 곳에서 실패했을 때.</b>
     *
     * <p>웹훅은 텔레그램에 이미 200을 준 상태라 재시도가 없다 — 여기서 아무것도 안 보내면
     * 사용자에게는 봇이 죽은 것과 구분되지 않는다. 명령의 제목을 이고 있어야 무엇에 대한
     * 답인지 알 수 있고, "잠시 후 다시"가 있어야 사용자가 할 일이 생긴다.
     *
     * <p>{@link #empty}와 다르다 — 그쪽은 "조회는 됐는데 값이 없다"이고 이쪽은 "조회 자체가
     * 실패했다"다. 사용자가 할 일이 다르므로 문장도 달라야 한다.
     */
    static String unavailable(Command command) {
        return head(command) + "지금은 답을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    /**
     * 출처 줄 — <b>둘 이상이면 한 줄에 하나씩 내려 적는다.</b>
     *
     * <p>{@code A · B}로 잇지 않는다. 그렇다고 사이를 빈 줄로 벌리지도 않는다 —
     * <b>출처는 여럿이어도 블록 하나</b>이기 때문이다. 이 통의 규칙이 그대로 걸린다:
     * 빈 줄은 블록 사이, 한 줄은 블록 안. 그래서 출처 덩어리와 기준 시각 사이만 빈 줄이다.
     *
     * <p><b>증시와 날씨가 이 하나를 함께 쓴다</b> — 통마다 규칙이 갈리지 않게 하려고 뽑았다.
     * 제네릭 소거 탓에 {@code List}를 받는 오버로드가 성립하지 않아, 타입을 벗긴 표시 이름만 받는다.
     *
     * <p>이스케이프는 <b>여기서 이름마다</b> 한다. 이어붙인 뒤 통째로 하면 구분자까지 대상이
     * 되는 모양이라 의도가 흐리고, 호출부가 한 번 더 하면 {@code S&P 500}이 두 번 이스케이프된다.
     */
    static String sources(Stream<String> displayNames) {
        return displayNames.map(Html::escape).collect(Collectors.joining("\n"));
    }

    /** 등락률을 제 줄에 붙인다 — 값과 원화 환산 다음이다. 없으면 붙이지 않는다. */
    static void appendChangeLine(StringBuilder lines, BigDecimal percent) {
        String change = change(percent);
        if (!change.isEmpty()) {
            lines.append("\n").append(change);
        }
    }

    /**
     * 굵은 제목 한 줄과 그 아래 빈 줄 — <b>모든 메시지가 이것으로 시작한다.</b>
     *
     * <p>실패·안내 답에도 붙인다. 같은 명령의 답인데 성공했을 때만 제목이 있으면 모양이
     * 갈리고, 그룹 채팅에서 맨몸 문장 하나만 튀어나오면 무엇에 대한 답인지 알 수 없다.
     */
    static String head(Command command) {
        return title(command) + "\n\n";
    }

    /**
     * 보여줄 값이 하나도 없을 때 — <b>네 통이 같은 문장으로 답한다.</b>
     *
     * <p>예전에는 포매터마다 달랐다: 증시·날씨는 굵은 제목 한 마디만 내보내고(값도 출처도
     * 시각도 없이), 코인은 거기에 빈 줄이 붙고, 뉴스만 사실을 말했다. 지금은 호출자가 다
     * 막고 있어 도달하지 않지만, <b>넷이 제각각인 것 자체가 다음에 누가 막는 걸 잊었을 때
     * 무슨 일이 벌어질지 알 수 없게 만든다.</b>
     */
    static String empty(Command command) {
        return head(command) + "지금은 가져올 수 있는 값이 없습니다.";
    }

    /**
     * 제목 줄만. 아래에 무리를 바로 붙이는 통(증시·코인·뉴스 브리핑)이 쓴다.
     *
     * <p>제목 문자열이 {@link Command}에만 있으므로 검색 답과 브리핑 답의 제목이 갈릴 수 없다.
     *
     * <p>부르는 곳은 뉴스({@link #newsTitle})·증시·날씨·도움말, 그리고 {@link #section}이다 —
     * 코인은 {@link #section}을 거쳐 간접적으로 쓴다.
     *
     * <p><b>검색어를 붙이지 않는다.</b> 붙이던 때가 있었는데({@code 증시 '삼성전자'}) 답이
     * <b>답글로</b> 나가서 텔레그램이 원 명령을 바로 위에 인용해 그린다 — 같은 말을 두 번 하는
     * 것이었다({@code TelegramClient}의 {@code replyToMessageId} 참조). 덕분에 검색 답과 알람이
     * 글자 그대로 같은 제목을 쓴다.
     */
    static String title(Command command) {
        return "<b>" + Html.escape(command.section()) + "</b>";
    }

    /**
     * 등락률 한 조각 — <b>상승 🔴 +1.20% / 하락 🔵 -1.20% / 보합 0.00%</b>.
     *
     * <p><b>부호를 숫자 옆에 붙인다.</b> 원과 겹친다고 생략했었는데, 이모지는 기기·글꼴에 따라
     * 색이 흐릿하거나 아예 안 뜨는 곳이 있어 그때는 방향이 통째로 사라진다. 부호는 어디서나
     * 같은 글자다. 보합은 방향이 없으므로 부호도 붙이지 않는다.
     *
     * <p><b>{@code null}이면 빈 문자열이다.</b> "못 구했다"와 "보합(0%)"은 다른 말이므로
     * 못 구한 값을 {@code 0.00%}로 찍어서는 안 된다. 그때는 시세만 나간다.
     *
     * <p>소수 둘째 자리로 맞춘다. 출처마다 자릿수가 제각각이라
     * ({@code 0.99586}·{@code 4.89}·{@code -1.451}) 그대로 두면 한 화면에서 정밀도가 들쭉날쭉해진다.
     */
    static String change(BigDecimal percent) {
        if (percent == null) {
            return "";
        }
        BigDecimal rounded = percent.setScale(2, RoundingMode.HALF_UP);
        int direction = rounded.signum();
        if (direction == 0) {
            return "0.00%";
        }
        return (direction > 0 ? "🔴 +" : "🔵 -") + rounded.abs().toPlainString() + "%";
    }

    /**
     * 원 단위로 반올림한다 — 소수점 아래 원화는 읽는 사람에게 의미가 없다.
     *
     * <p>⚠️ <b>1원 미만은 예외다.</b> 그대로 반올림하면 {@code 0 KRW}가 찍히는데, 그건
     * "환산하면 0원"이라는 <b>값</b>으로 읽힌다 — 실제로 도지코인 {@code 0.00035 USDT}가
     * {@code 0 KRW}로 나갔다. {@code 0}을 값으로 내보내지 않는다는 규칙({@code Price})이
     * 화면에서도 같이 걸려야 한다. 그때만 두 자리를 남긴다.
     */
    static BigDecimal krw(BigDecimal usd, FxRate fx) {
        BigDecimal converted = usd.multiply(fx.rate());
        return converted.compareTo(BigDecimal.ONE) < 0
                ? converted.setScale(2, RoundingMode.HALF_UP)
                : converted.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 천 단위로 끊는다. 코인 가격은 자릿수 폭이 커서(89,848,000 ~ 0.5) 구분이 없으면 읽기 어렵다.
     *
     * <p>소수점 이하는 있을 때만 남긴다 — {@code 89848000.00000000}을 그대로 내보내면 안 된다.
     */
    static String money(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        BigDecimal trimmed = amount.stripTrailingZeros();
        int scale = Math.max(trimmed.scale(), 0);
        // ⚠️ 소수점이 남았으면 최소 두 자리로 맞춘다. 뒤 0을 그냥 떼면 <b>같은 칸의 정밀도가
        //    출처에 따라 갈린다</b> — 환율이 1,412.17과 1,389.4로, 지수가 3,182.44와 6,481.4로
        //    나갔다. 온도 21.0이 21로 줄어 oneDecimal을 만들게 한 그 버그와 같은 부류인데
        //    가격·환율에는 안 걸려 있었다.
        //    떼는 것 자체는 없앨 수 없다 — 업비트가 89848000.00000000처럼 scale 8로 주므로
        //    그대로 내보내면 0이 여덟 개 붙는다. 정수가 된 값은 그대로 정수로 둔다.
        if (scale > 0) {
            scale = Math.max(scale, 2);
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.KOREA);
        format.setMinimumFractionDigits(scale);
        format.setMaximumFractionDigits(scale);
        return format.format(trimmed);
    }

    /**
     * 온도·강수량처럼 <b>자릿수가 정해진 값</b> — 소수 한 자리로 맞춘다.
     *
     * <p><b>{@link #money}를 쓰면 안 된다.</b> 그쪽은 뒤 0을 떼므로 {@code 21.0}이 {@code 21}이
     * 되어 바로 옆 {@code 26.4}와 자릿수가 갈린다. 실제로 그 상태였고 테스트에도
     * {@code 22°C / 30.5°C}로 굳어 있었다 — 한 줄 안에서 정밀도가 들쭉날쭉했다.
     *
     * <p>둘을 나누는 기준은 <b>값의 폭</b>이다. 온도와 강수량은 폭이 좁아 자릿수를 고정해도
     * 잃는 것이 없지만, 가격은 89,848,000부터 0.5까지라 고정하면 어느 한쪽이 망가진다.
     * {@code change()}가 등락률을 둘째 자리로 맞추는 것과 같은 판단이다.
     */
    static String oneDecimal(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        // ⚠️ 반올림을 명시한다. NumberFormat 기본값은 HALF_EVEN인데 이 클래스의 나머지
        //    (change·krw·premium)는 전부 HALF_UP이라, 강수량 0.25가 0.2로 나가고 있었다 —
        //    한 화면 안에 반올림 규칙이 둘 있을 이유가 없다
        NumberFormat format = NumberFormat.getNumberInstance(Locale.KOREA);
        format.setRoundingMode(RoundingMode.HALF_UP);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(amount);
    }
}
