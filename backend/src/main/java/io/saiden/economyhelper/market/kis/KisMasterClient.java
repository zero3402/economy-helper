package io.saiden.economyhelper.market.kis;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 한국투자증권 <b>종목 마스터 파일</b> — 국내 상장 종목 전부의 「단축코드 · 한글명 · 시가총액」.
 *
 * <p><b>왜 이것이 필요한가.</b> KIS에는 종목명 검색이 없고(조회가 언제나 코드 → 이름 방향이다),
 * 이름으로 찾던 공공데이터포털 주식시세정보는 <b>ETF를 아예 주지 않는다</b>(실측 2026-08-28:
 * {@code likeItmsNm=KODEX}·{@code 나스닥100}·{@code TIME}이 전부 0건, {@code 삼성전자}는 2건).
 * 그래서 {@code /stock 타임나스닥100}은 어느 단에서도 코드를 얻지 못했다. 이 파일이 그 빈자리를
 * 메운다 — 키가 없고, 두 파일 합쳐 220KB이며, ETF 1,164건이 다 들어 있다
 * ({@code 426030 TIME 미국나스닥100액티브}).
 *
 * <p><b>형식은 실측으로 확정했다</b>(공식 파이썬 예제의 꼬리 길이와 <b>한 글자</b> 다르다 —
 * 228/222로 자르면 그룹코드가 {@code " E"}·{@code " S"}로 한 칸 밀린다):
 *
 * <pre>
 * 행 = [단축코드 9][표준코드 12][한글명 가변] + 고정폭 꼬리
 * 꼬리 길이   KOSPI 227 · KOSDAQ 221
 * 꼬리[0:2]   그룹코드 — ST 주권 · EF ETF · EN ETN · BC 수익증권 · RT 리츠 · FS 외국주권
 * 꼬리[-15:-6] 시가총액(억) — 005930 → 015551101 · 426030 → 000024944
 * </pre>
 *
 * <p><b>6자 단축코드만 남긴다.</b> 그것이 KIS 국내 시세({@code FHKST03010100})가 받는 코드
 * 모양이다 — 영숫자({@code 0019K0})도 받는 것을 실측했다. 9자(수익증권 {@code F70100030})·
 * 7자(ETN {@code Q500093})는 그 경로가 받지 않으므로 색인에 있어도 쓸 곳이 없다.
 *
 * <p><b>실패는 던진다.</b> 브레이커가 세야 하고, 삼키는 것은 {@code StockService}가 한다 —
 * 다음 수(공공데이터포털 이름 검색)가 거기 있다. 재시도는 걸지 않는다(다음 출처가 있다).
 * 리미터도 없다 — 키·한도가 없고 6시간 캐시라 하루 네 번이 상한이다.
 *
 * <p>⚠️ URL 끝에 슬래시를 두지 않는다. 우리 {@code RestClient}는 리다이렉트를 따라가지 않아
 * 308이 오면 본문이 {@code Redirecting...} 15바이트가 되고 파싱이 죽는다 — CoinDesk 피드에서
 * 실제로 겪은 사고다.
 */
@Component
public class KisMasterClient {

    private static final Logger log = LoggerFactory.getLogger(KisMasterClient.class);

    static final String KOSPI_FILE = "/kospi_code.mst.zip";
    static final String KOSDAQ_FILE = "/kosdaq_code.mst.zip";

    /** 고정폭 꼬리의 길이 — 시장별로 다르다(실측). */
    static final int KOSPI_TAIL = 227;
    static final int KOSDAQ_TAIL = 221;

    /** 파일 인코딩. 한글명이 이걸로 온다 — UTF-8로 읽으면 이름이 전부 깨진다. */
    private static final Charset CP949 = Charset.forName("MS949");

    private static final int CODE_END = 9;
    private static final int NAME_START = 21;
    private static final int GROUP_LENGTH = 2;
    /** 꼬리 끝에서 센 시가총액 칸. 뒤에 그룹사코드(3)·신용한도초과(1)·담보대출(1)·대주(1)가 붙는다. */
    private static final int MARKET_CAP_FROM_END = 15;
    private static final int MARKET_CAP_LENGTH = 9;

    /** KIS 국내 시세가 받는 코드 모양. 첫 자가 숫자라 미국 티커(영문 1~5자)와 겹치지 않는다. */
    private static final Pattern LISTED_CODE = Pattern.compile("[0-9][0-9A-Z]{5}");

    private final RestClient restClient;

    public KisMasterClient(RestClient.Builder builder,
                           @Value("${economy-helper.market.kis.master-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * KOSPI·KOSDAQ 상장 종목 전부.
     *
     * <p>키가 {@code 'all'} 하나다 — 두 파일을 따로 담으면 한쪽만 낡는 순간이 생긴다.
     *
     * @throws IllegalStateException 어느 파일이든 못 받거나 한 행도 못 읽었을 때
     */
    @Cacheable(cacheNames = CacheNames.KR_LISTINGS, key = "'all'", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "kisMaster")
    public List<Listing> listings() {
        List<Listing> all = new ArrayList<>();
        all.addAll(parse(download(KOSPI_FILE), KOSPI_TAIL));
        all.addAll(parse(download(KOSDAQ_FILE), KOSDAQ_TAIL));
        if (all.isEmpty()) {
            throw new IllegalStateException("KIS 종목 마스터에서 한 행도 읽지 못했습니다");
        }
        log.info("[kis] 종목 마스터 {}건을 읽었습니다", all.size());
        return List.copyOf(all);
    }

    private byte[] download(String file) {
        byte[] body = restClient.get().uri(file).retrieve().body(byte[].class);
        if (body == null || body.length == 0) {
            throw new IllegalStateException("KIS 종목 마스터 " + file + "이 비어 왔습니다");
        }
        return body;
    }

    /**
     * zip 안의 첫 항목을 cp949로 읽어 행마다 자른다.
     *
     * <p>꼬리보다 짧은 행은 건너뛴다 — 마지막 줄바꿈이나 깨진 행이 파싱 전체를 죽이면 안 된다.
     */
    static List<Listing> parse(byte[] zip, int tailLength) {
        List<Listing> listings = new ArrayList<>();
        for (String line : unzip(zip).split("\\r?\\n")) {
            int split = line.length() - tailLength;
            if (split <= NAME_START) {
                continue;
            }
            String code = line.substring(0, CODE_END).strip();
            if (!LISTED_CODE.matcher(code).matches()) {
                continue;
            }
            String tail = line.substring(split);
            listings.add(new Listing(code,
                    line.substring(NAME_START, split).strip(),
                    tail.substring(0, GROUP_LENGTH).strip(),
                    marketCap(tail)));
        }
        return listings;
    }

    private static long marketCap(String tail) {
        int from = tail.length() - MARKET_CAP_FROM_END;
        String digits = tail.substring(from, from + MARKET_CAP_LENGTH).strip();
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unzip(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                throw new IllegalStateException("KIS 종목 마스터 zip에 항목이 없습니다");
            }
            return new String(in.readAllBytes(), CP949);
        } catch (IOException e) {
            throw new UncheckedIOException("KIS 종목 마스터 zip을 풀지 못했습니다", e);
        }
    }

    /**
     * 상장 종목 하나.
     *
     * @param code      단축코드 {@code 005930}·{@code 0019K0} — KIS 국내 시세의 조회 키
     * @param name      한글 상장명 {@code TIME 미국나스닥100액티브}. 브랜드는 영문 그대로 온다
     * @param group     그룹코드 {@code ST}·{@code EF}·{@code EN}. 화면에 안 쓰고 로그·테스트가 본다
     * @param marketCap 시가총액(억). 동명 후보를 가르는 내부 신호 — 공공데이터포털의 {@code mrktTotAmt}와 같은 자리
     */
    public record Listing(String code, String name, String group, long marketCap) {

        /** ETF·ETN인가 — 증권사가 목표주가를 내지 않는 것들이다. 전망을 물어도 늘 0행이다(실측 426030). */
        public boolean fund() {
            return "EF".equals(group) || "EN".equals(group);
        }
    }
}
