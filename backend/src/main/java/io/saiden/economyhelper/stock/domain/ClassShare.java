package io.saiden.economyhelper.stock.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 클래스 주식 티커 — 티커 1~5자 + 구분자 + 클래스 1~2자({@code BRK.B}·{@code bf-b}·{@code BRK/B}).
 *
 * <p>검색({@code StockService})과 KIS 어댑터가 <b>같은 판정</b>을 쓴다. 두 벌일 때는 어댑터 쪽이 대문자만
 * 받아, LLM이 소문자로 넘긴 {@code brk.b}가 KIS 표기로 안 바뀌고 빈손이 됐다.
 *
 * @param ticker 대문자 티커({@code BRK})
 * @param klass  대문자 클래스({@code B})
 */
public record ClassShare(String ticker, String klass) {

    /** 끝에 붙은 물음표 같은 군더더기 하나는 허용한다({@code BRK.B?}). */
    private static final Pattern SHAPE = Pattern.compile("([A-Z]{1,5})[./-]([A-Z]{1,2})[?!.,]?");

    /** 토큰 하나가 클래스 주식 모양이면 그것. 대소문자는 가리지 않는다. */
    public static Optional<ClassShare> of(String token) {
        Matcher matcher = SHAPE.matcher(token.toUpperCase(Locale.ROOT));
        return matcher.matches()
                ? Optional.of(new ClassShare(matcher.group(1), matcher.group(2)))
                : Optional.empty();
    }

    /** 사용자·화면이 쓰는 점 표기({@code BRK.B}). */
    public String dotted() {
        return ticker + "." + klass;
    }

    /** KIS 해외 마스터의 표기({@code BRK/B}) → ADR-0001. */
    public String kis() {
        return ticker + "/" + klass;
    }
}
