package io.saiden.economyhelper.text;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 사용자가 친 검색어를 비교 가능한 형태로 다듬는다.
 *
 * <p>실제 데이터(KOSPI 804 + NASDAQ 3,071종목)에 돌려 보고 만든 규칙이다. 아래 넷은
 * <b>전부 실측에서 실패했던 입력</b>이고, 각각을 고친 결과가 이 클래스다.
 *
 * <ol>
 *   <li>{@code apple stock price} — 접미사를 한 번만 떼면 {@code applestock}에서 멈춘다.
 *       <b>더 이상 줄지 않을 때까지 반복</b>해야 한다
 *   <li>{@code 삼성전자 주가 알려줘} — 조사 {@code 가}를 떼다 {@code 삼성전자주}로 망가졌다.
 *       <b>단일 글자 조사는 종목명을 부순다.</b> {@link #forLookup}에서는 떼지 않는다
 *   <li>{@code 오늘 테슬라 주가} — 접미사만 보면 앞의 {@code 오늘}이 남는다. <b>접두어도 떼야 한다</b>
 *   <li>긴 접미사보다 짧은 접미사가 먼저 걸리면 엉뚱하게 잘린다 → <b>길이 내림차순</b>으로 보고,
 *       하나 뗄 때마다 처음부터 다시 훑는다
 * </ol>
 *
 * <p>조사 제거가 아예 쓸모없는 건 아니다 — {@code /news}는 {@code 금리}·{@code 금리는}·{@code 금리가}가
 * 지금 <b>각각 따로 캐시돼</b> 같은 개념에 Gemini를 세 번 태운다. 그쪽은 종목명처럼 정확히
 * 맞출 필요가 없으므로 {@link #forSearchToken}에서 따로 떼어 준다.
 */
public final class QueryNormalizer {

    /** 종목명·코인명에 없는 문자는 전부 버린다 — 공백·따옴표·물음표 따위가 매칭을 방해한다. */
    private static final Pattern NOISE = Pattern.compile("[^0-9a-z가-힣]");

    /**
     * 검색어에 얹히는 군더더기. <b>길이 내림차순으로 본다</b> — {@code 현재가}보다 {@code 가}가
     * 먼저 걸리면 {@code 삼성전자현재가}가 {@code 삼성전자현재}로 남는다.
     *
     * <p>단일 글자는 <b>거의</b> 넣지 않는다. 위 2번 사례가 그래서 터졌다 — 예외는 {@code 몇}뿐이고, 그건 어느 종목명·지명에도 안 들어가는 의문사라 안전하다.
     */
    private static final List<String> SUFFIXES = sortedByLengthDesc(
            "주가", "주식", "가격", "시세", "얼마", "알려줘", "알려", "현재가", "종가", "몇",
            "stock", "stocks", "price", "prices", "quote", "share", "shares");

    /** {@code 오늘 테슬라 주가}의 {@code 오늘}. 접미사만 떼면 이게 남아 매칭이 통째로 실패한다. */
    private static final List<String> PREFIXES = sortedByLengthDesc(
            "오늘", "현재", "지금", "실시간", "current", "today");

    /**
     * {@code /news} 토큰에서만 떼는 조사.
     *
     * <p>{@link #forLookup}에 쓰면 안 된다 — {@code 삼성전자주가}에서 {@code 가}를 떼어
     * {@code 삼성전자주}로 만든다. 검색어 토큰은 정확히 맞출 필요가 없어 여기서만 허용한다.
     */
    private static final List<String> PARTICLES = sortedByLengthDesc(
            "은", "는", "이", "가", "을", "를", "의", "에", "도", "만", "와", "과", "로", "으로", "에서");

    /** 조사를 떼고 남는 최소 길이. {@code 인도} → {@code 인}처럼 한 글자만 남으면 원래 단어를 부순 것이다. */
    private static final int MIN_LENGTH_AFTER_PARTICLE = 2;

    private QueryNormalizer() {
    }

    /**
     * 종목·코인 조회용. 군더더기를 걷어낸 형태를 <b>원형과 함께</b> 돌려준다.
     *
     * <p>둘 다 주는 이유는 어느 쪽이 맞을지 미리 알 수 없어서다. {@code 현대차}는 원형이 정답이고
     * ({@code 차}를 접미사로 넣지 않았지만 비슷한 함정이 언제든 생긴다), {@code 애플 주가}는
     * 다듬은 쪽이 정답이다. 호출자가 <b>순서대로 시도</b>하면 된다.
     *
     * @return 1개 또는 2개. 첫 번째가 원형, 두 번째가 군더더기를 뗀 형태(달라졌을 때만)
     */
    public static List<String> forLookup(String query) {
        String base = normalize(query);
        if (base.isEmpty()) {
            return List.of();
        }
        String trimmed = stripAffixes(base);
        return trimmed.equals(base) ? List.of(base) : List.of(base, trimmed);
    }

    /** {@code /news} 검색어 토큰용 — 조사까지 뗀다. 캐시 키가 되므로 표기가 흔들리면 적중률이 떨어진다. */
    public static String forSearchToken(String token) {
        String base = normalize(token);
        for (String particle : PARTICLES) {
            if (base.endsWith(particle) && base.length() - particle.length() >= MIN_LENGTH_AFTER_PARTICLE) {
                return base.substring(0, base.length() - particle.length());
            }
        }
        return base;
    }

    /** NFKC로 전각·호환 문자를 통일하고, 소문자로 맞추고, 비교에 쓸 수 없는 문자를 버린다. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String unified = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOISE.matcher(unified).replaceAll("");
    }

    /**
     * 접두어·접미사를 <b>더 이상 줄지 않을 때까지</b> 뗀다.
     *
     * <p>한 번만 떼면 {@code applestockprice}가 {@code applestock}에서 멈춘다.
     * 하나 뗄 때마다 처음부터 다시 훑는 이유는 짧은 것을 뗀 뒤에 긴 것이 드러날 수 있어서다.
     */
    static String stripAffixes(String normalized) {
        String current = normalized;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (current.endsWith(suffix) && current.length() > suffix.length()) {
                    current = current.substring(0, current.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
            if (changed) {
                continue;
            }
            for (String prefix : PREFIXES) {
                if (current.startsWith(prefix) && current.length() > prefix.length()) {
                    current = current.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        }
        return current;
    }

    private static List<String> sortedByLengthDesc(String... values) {
        return List.of(values).stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
    }
}
