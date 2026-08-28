package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.kis.KisMasterClient;
import io.saiden.economyhelper.market.kis.KisMasterClient.Listing;
import io.saiden.economyhelper.text.QueryNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「이름 → 종목코드」 색인 — {@link KisMasterClient}가 준 목록 위의 <b>순수 계산</b>이다.
 *
 * <p>규칙은 둘이고, 둘 다 저장소가 다른 곳에서 이미 쓰는 것이다.
 *
 * <ol>
 *   <li>정규화한 이름이 <b>정확히 같은</b> 상장이 있으면 그것 — 여럿이면 시가총액 1위
 *   <li>아니면 질의를 문자 종류(숫자·영문·한글) 단위 토큰으로 쪼개 <b>토큰 전부를 품는</b> 상장 중
 *       <b>시가총액 1위</b> — 공공데이터포털 이름 검색({@code DataGoStockClient.best})과 코인
 *       ({@code 24시간 거래대금 1위})이 동명 후보를 가르는 그 규칙이다
 * </ol>
 *
 * <p><b>LLM 없이 {@code 타임나스닥100}은 빈손이다</b> — {@code 타임}과 {@code TIME}은 다른 글자다.
 * 그 소리를 맞추는 것이 {@link StockResolver}의 몫이고({@code 제피 → JEPI}와 같은 자리),
 * 여기는 그 답({@code TIME 미국나스닥100액티브})을 코드로 바꾸는 일만 한다. 브랜드 대응표를
 * 두지 않는 이유는 {@code CLAUDE.md}의 한글 티커 절에 있다.
 *
 * <p>{@link #agrees}는 LLM이 준 코드와 이름이 <b>서로 다른 종목을 가리키는지</b> 보는 데 쓴다 —
 * ETF는 이름이 비슷한 코드가 수십 개라 <b>존재하는 틀린 코드</b>가 흔하고, 그러면 KIS가 멀쩡히
 * 답해 다른 ETF가 나간다. 틀린 값이 빈손보다 나쁘다.
 */
@Component
public class StockListings {

    /** 숫자·영문·한글 무리 하나가 토큰 하나다. 정규화 뒤라 다른 문자는 없다. */
    private static final Pattern TOKEN = Pattern.compile("[0-9]+|[a-z]+|[가-힣]+");

    private final Supplier<List<Listing>> source;

    @Autowired
    public StockListings(KisMasterClient master) {
        this(master::listings);
    }

    /** 목록을 직접 준다 — 테스트와, 마스터 없이 돌아야 하는 자리가 쓴다. */
    public StockListings(Supplier<List<Listing>> source) {
        this.source = source;
    }

    /**
     * @return 이름으로 찾은 상장 하나. 없으면 빈손 — 「없다」는 장애가 아니다
     * @throws RuntimeException 마스터를 못 받았을 때(브레이커 열림 포함). 부르는 쪽이 삼킨다
     */
    public Optional<Listing> find(String query) {
        return find(source.get(), query);
    }

    /** 코드로 찾는다 — 대소문자를 가리지 않는다(정규화가 소문자로 내린다). */
    public Optional<Listing> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return source.get().stream().filter(listing -> listing.code().equalsIgnoreCase(code)).findFirst();
    }

    static Optional<Listing> find(List<Listing> listings, String query) {
        String wanted = QueryNormalizer.normalize(query);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<Listing> exact = new ArrayList<>();
        List<Listing> containing = new ArrayList<>();
        List<String> tokens = tokens(wanted);
        for (Listing listing : listings) {
            String name = QueryNormalizer.normalize(listing.name());
            if (name.equals(wanted)) {
                exact.add(listing);
            } else if (containsAll(name, tokens)) {
                containing.add(listing);
            }
        }
        return largest(exact.isEmpty() ? containing : exact);
    }

    /** {@code name}의 토큰이 전부 상장명에 들어 있는가 — {@code 삼성전자}↔{@code 삼성전자}는 참, {@code 네이버}↔{@code NAVER}는 거짓. */
    public static boolean agrees(Listing listing, String name) {
        String wanted = QueryNormalizer.normalize(name);
        return !wanted.isEmpty() && containsAll(QueryNormalizer.normalize(listing.name()), tokens(wanted));
    }

    private static boolean containsAll(String name, List<String> tokens) {
        return tokens.stream().allMatch(name::contains);
    }

    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static Optional<Listing> largest(List<Listing> candidates) {
        return candidates.stream().max(Comparator.comparingLong(Listing::marketCap));
    }
}
