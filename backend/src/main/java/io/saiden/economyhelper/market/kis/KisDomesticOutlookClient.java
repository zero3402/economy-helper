package io.saiden.economyhelper.market.kis;

import java.util.Objects;
import io.saiden.economyhelper.support.Fetched;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.DomesticOutlookClient;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockOutlook.Dividend;
import io.saiden.economyhelper.market.StockSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 국내 종목의 <b>목표주가·배당</b> — KIS {@code invest-opinion}과 예탁원정보(배당일정) {@code ksdinfo/dividend}.
 *
 * <p>엔드포인트 이름이 「투자의견」이라 로그·예외 문구는 그 이름을 그대로 쓰지만,
 * <b>그 응답에서 읽는 것은 목표가뿐이다</b>(아래 ⚠️ 참고). 머리글이 한동안
 * 「목표주가·투자의견」이라 <b>같은 파일 안에서 스스로를 반박하고 있었다.</b>
 *
 * <p><b>둘 다 모의 계정에서도 200이다</b>(실측 투자의견 2026-08-20 · 배당일정 2026-09-07). 문서가
 * 「실전 전용 엔드포인트가 막힌다」고 적어 둔 목록에 넣어 짐작할 뻔했는데, <b>막힌 것과 안 해 본 것은 다르다.</b>
 *
 * <p><b>투자의견 응답은 컨센서스가 아니라 발표 건이다</b>(실측 2026-08-21, 삼성전자 7~8월 12행):
 *
 * <pre>
 * {"stck_bsop_date":"20260810","mbcr_name":"키움",
 *  "invt_opnn":"BUY","invt_opnn_cls_code":"2","hts_goal_prc":"350000", …}
 * </pre>
 *
 * 그래서 {@link InvestOpinions}가 증권사별 최신 한 건만 남겨 접는다 — 자주 내는 증권사가
 * 여러 표를 갖지 않게 하려는 것이다.
 *
 * <p>⚠️ <b>그 응답에서 읽는 것은 목표가뿐이다.</b> 같은 행에 {@code invt_opnn}(의견 글자)이
 * 함께 오지만 <b>읽지 않는다</b> — 투자의견을 화면에서 걷어냈기 때문이다. 다시 넣을 일이
 * 있으면 {@code invt_opnn_cls_code}를 등급으로 쓰지 말 것: 실측에서 코드 하나에
 * {@code Strong BUY}·{@code Hold}·{@code Outperform}·{@code Buy}가 섞여 있었고, 같은 응답
 * 안에서 표기도 갈렸다({@code "BUY"} 키움·삼성 / {@code "매수"} 한국투자).
 *
 * <p><b>배당은 예탁원정보(배당일정)가 준다</b> — 실측 2026-09-07, 삼성전자 {@code F_DT=20260301}:
 *
 * <pre>
 * {"output1":[{"record_date":"20260630","sht_cd":"005930","isin_name":"삼성전자","divi_kind":"분기",
 *              "face_val":"100","per_sto_divi_amt":"374","divi_rate":"374.00","stk_divi_rate":"0.00",
 *              "divi_pay_dt":"2026/08/28","stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통","high_divi_gb":"Y"},
 *             {"record_date":"20260331", … "per_sto_divi_amt":"372","divi_pay_dt":"2026/05/29", …}],
 *  "rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다."}
 * </pre>
 *
 * <ul>
 *   <li>⚠️ <b>날짜 모양이 한 행 안에서 갈린다</b> — {@code record_date}는 {@code yyyyMMdd}, {@code divi_pay_dt}는
 *       {@code yyyy/MM/dd}. 하나로 읽으면 한쪽이 조용히 죽는다
 *   <li>⚠️ <b>{@code F_DT}·{@code T_DT}는 기준일을 거른다.</b> 지급일은 기준일 뒤 두 달~넉 달이라(실측: 결산 기준일
 *       {@code 20251231} → 지급 {@code 2026/04/17}) 되짚어야 「지급일만 남은 분기」가 들어오고,
 *       <b>연 1회 배당은 되짚기가 365일을 넘어야</b> 한 건이라도 들어온다 —
 *       {@link #DIVIDEND_LOOKBACK_DAYS}에 그 산술이 있다(좁게 뒀다가 「배당일이 안 나온다」로
 *       신고받았다). 앞으로는 {@value #DIVIDEND_LOOKAHEAD_DAYS}일을 본다
 *   <li>⚠️ <b>배당금 {@code "0"}·지급일 {@code ""}인 행이 온다</b>(실측 SK하이닉스 결산 {@code 20251231}) — 아직 안
 *       정해진 것이고 값이 아니다. 고르는 규칙은 {@link Dividend#nextOf}가 든다(FMP와 같은 규칙)
 *   <li>⚠️ 배열 이름이 {@code output1}이다 — 투자의견은 {@code output}이다. 잘못 적으면 오류 없이 빈 목록이다
 *   <li>{@code SHT_CD}가 종목을 거른다(실측: 우선주 {@code 005935}는 제 행만 온다). 한 종목·한 해면 몇 행이라
 *       연속조회({@code tr_cont=M})는 일어나지 않는다(실측 {@code E})
 * </ul>
 *
 * <p>⚠️ <b>종목당 KIS 호출이 둘이 됐다</b> — 간격 1초가 하나 더 든다. 12시간 캐시({@code kis-outlook})가
 * 그 대가의 방어이고, 브리핑의 국내 종목 둘은 첫 조회에 2초를 더 낸다.
 *
 * <p>⚠️ <b>ETF·ETN에는 투자의견만 건너뛴다 — 배당은 묻는다.</b> 색인이 알려 준 종목에 목표가를 물으면
 * 늘 0행이지만(실측 426030), <b>분배금은 같은 배당일정 응답에 실제로 온다</b>
 * (실측 2026-09-08, 코드가 보내는 ±180일 창 — KODEX 200(069500) 두 행 — 기준일 {@code 20260731}·지급 {@code 2026/08/04}·183원 /
 * {@code 20260430}·{@code 2026/05/06}·446원). 「ETF에는 전망이 없다」로 한 덩어리로 건너뛰면
 * <b>있는 값을 알면서 버린다.</b>
 *
 * <p>⚠️ 처음에는 이 근거로 {@code 426030}의 {@code 20251230} 행을 들었는데, 그것은
 * {@code F_DT=20250101}로 <b>넓게 물어야</b> 나오는 행이고 <b>코드가 보내는 창 밖</b>이다
 * (같은 종목을 ±180일로 물으면 <b>0행</b>이다 — 연 1회 분배라 다음 행이 아직 안 올라왔다).
 * <b>실측은 코드가 보내는 파라미터로 재야 한다.</b>
 *
 * <p>캐시 열쇠는 <b>코드뿐</b>이다({@code fund}를 안 섞는다). 같은 코드에 다른 플래그가 올 수는 있지만
 * (이름으로 찾으면 참, 코드로 찾으면 거짓) ETF의 투자의견이 0행이라 <b>결과가 같다</b> — 열쇠에 넣으면
 * ETF마다 항목이 둘이 되고 그 플래그는 우리 색인의 산물이라 판 번호까지 딸려 온다.
 *
 * <p>⚠️ <b>하나라도 받았으면 그것으로 답하고 담는다.</b> 한때 「하나라도 실패하면 던진다」로
 * 좁혔다 — 반쪽이 12시간 캐시에 굳는 것을 막으려는 것이었는데 <b>대가가 더 컸다</b>: 던지면
 * 아무것도 안 담겨 조회마다 <b>KIS 문 2초</b>를 다시 쓰고(그 문은 시세와 <b>공유</b>다),
 * 값이 안 바뀌는 실패(파싱 같은 것)는 <b>영원히 되풀이되며 브레이커를 태운다.</b>
 * 치르는 값은 「500 한 번에 배당 줄이 최대 반나절 안 보인다」이고 스스로 낫는다 —
 * 보충 한 줄의 신선도보다 <b>문과 브레이커를 지키는 것</b>이 앞이다.
 * <b>둘 다 실패했을 때만</b> 던지고, 그때 <b>원래 예외를 그대로</b> 올린다.
 * (되돌린 것이다 — 두 번째·세 번째 검증이 그 대가를 짚었다.)
 *
 * <p>⚠️ <b>실패를 삼키지 않는다 — 던진다.</b> {@link DomesticOutlookClient}가 「빈 값으로
 * 실패한다」고 적혀 있었지만 그대로 하면 아래 {@code @CircuitBreaker}가 <b>정상 반환을 보고
 * 성공을 센다</b>. 실패율이 영원히 0이라 브레이커가 열리지 않고, KIS가 죽어 있는 동안 조회마다
 * 간격 1초를 헛되이 지불한다 — {@code HackerNewsApi}가 실제로 그 상태였고 그 브레이커의
 * 설정값이 전부 죽은 값이었다. 그래서 <b>삼키는 일은 {@code StockService}가 한다.</b>
 * 화면에서 「의견이 없는 종목」과 「조회 실패」가 같은 결과(그 줄이 없음)라는 것은 여전히
 * 맞고, 그 판단을 브레이커가 실패를 본 <b>뒤에</b> 하는 것뿐이다.
 *
 * <p>빈 값은 <b>값</b>이다 — 그 종목에 의견을 낸 증권사가 없고 잡힌 배당이 없다는 뜻이고, 그건 실패가 아니다.
 */
@Component
public class KisDomesticOutlookClient implements DomesticOutlookClient {

    private static final Logger log = LoggerFactory.getLogger(KisDomesticOutlookClient.class);

    private static final String OPINION_PATH = "/uapi/domestic-stock/v1/quotations/invest-opinion";
    private static final String OPINION_TR_ID = "FHKST663300C0";

    /** 화면 구분 코드. 투자의견 엔드포인트가 요구하는 고정값이다. */
    private static final String SCREEN_DIV = "16633";

    private static final String DIVIDEND_PATH = "/uapi/domestic-stock/v1/ksdinfo/dividend";
    private static final String DIVIDEND_TR_ID = "HHKDB669102C0";

    /**
     * 투자의견을 며칠치 물을지.
     *
     * <p>증권사는 분기 실적 즈음에 몰아서 내므로 한 달로는 의견이 없는 종목이 흔하다.
     * 실측(2026-08-21)으로 삼성전자가 7~8월 두 달에 12행이었다. 넉넉히 잡아도 응답이
     * 수십 행이라 비용이 같고, 접는 쪽에서 증권사별 최신 하나만 남기므로 오래된 것이
     * 화면에 새지 않는다.
     */
    private static final int OPINION_LOOKBACK_DAYS = 180;

    /**
     * 배당을 며칠치 <b>되짚을지</b> — <b>연 1회 주기(365)를 넘어야 한다.</b>
     *
     * <p>⚠️ <b>한동안 투자의견과 같은 180일을 썼고, 그것이 「배당일이 안 나온다」의 정체였다.</b>
     * 산술이 이렇다: {@code F_DT}는 <b>기준일</b>을 거르는데 <b>예탁원은 다음 기준일을 미리 올리지
     * 않는다</b>(실측: 삼성전자 다음 분기 0행). 그래서 화면에 쓸 수 있는 것은 사실상 <b>이미 지난
     * 마지막 기준일</b>이고, 그것이 {@code today − 180} 밖으로 나가는 순간 응답이 <b>0행</b>이 되어
     * 배당 블록이 통째로 사라진다.
     *
     * <p><b>국내 상장사 다수가 연 1회 결산배당</b>(기준일 12/31)이므로 그 종목들은
     * <b>6월 말부터 다음 기준일이 공시되는 12월까지 반년 가까이 빈칸</b>이었다. 실측
     * (2026-09-08)이 이미 그것을 보여 주고 있었다 — {@code 426030}(연 1회 분배, 마지막 기준일
     * {@code 20251230})이 <b>±180일 창에서 0행</b>인데 {@code F_DT=20250101}로 넓게 물으면 1행이다.
     * 그때는 「다음 행이 아직 안 올라왔다」로만 읽고 <b>창이 지난 것까지 잘라낸다는 것</b>을 못 봤다.
     *
     * <p>⚠️ <b>확인에 쓴 표본이 분기배당이어서 못 봤다.</b> 삼성전자·SK하이닉스는 분기마다 내므로
     * 마지막 기준일이 늘 180일 안이다 — <b>통과하는 표본으로만 확인한 셈</b>이다.
     *
     * <p>새 호출은 없다. 같은 요청의 파라미터만 넓히고 응답이 몇 행 늘 뿐이다(분기배당 6~7행 ·
     * 연 1회 1~2행). 옛 배당이 화면에 새지도 않는다 — {@link Dividend#nextOf}가 앞으로 올 것이
     * 없을 때 <b>가장 최근에 끝난 한 건</b>만 고른다.
     */
    private static final int DIVIDEND_LOOKBACK_DAYS = 400;

    /** 배당 기준일을 앞으로 며칠까지 볼지 — 기준일은 두어 주 전에 잡히므로 넉넉하다. */
    private static final int DIVIDEND_LOOKAHEAD_DAYS = 180;

    /** 배당 날짜를 자르는 달력 — 예탁원이 주는 것은 KRX 거래일이다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * {@code record_date}의 모양. {@code BASIC_ISO_DATE}는 <b>STRICT</b>라 {@code 20260231}을 거절한다.
     */
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * {@code divi_pay_dt}의 모양 — <b>한 행 안에서 {@code record_date}와 다르다.</b>
     *
     * <p>⚠️ <b>{@code uuuu}와 STRICT여야 한다.</b> {@code ofPattern("yyyy/MM/dd")}는 기본이 SMART라
     * {@code 2026/02/31}을 예외로 만들지 않고 <b>조용히 2026-02-28로 바꿔</b> 화면에 낸다 —
     * 없는 날을 있는 날로 적는 셈이고, 같은 행의 {@code record_date}(STRICT)와 판정이 갈린다.
     * STRICT에서는 {@code yyyy}(연대 기준 연도)가 연대 없이 못 풀리므로 {@code uuuu}를 쓴다.
     */
    private static final DateTimeFormatter SLASHED = DateTimeFormatter
            .ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);

    private final KisCall kis;
    private final Clock clock;

    public KisDomesticOutlookClient(KisCall kis, Clock clock) {
        this.kis = kis;
        this.clock = clock;
    }

    /**
     * @param code 6자리 종목코드
     * @param fund 색인이 ETF·ETN이라고 알려 줬나 — 참이면 투자의견을 <b>안 묻고</b> 배당만 묻는다
     * @return 접은 전망. 의견을 낸 증권사도 잡힌 배당도 없으면 빈 값 — 그건 값이고 실패가 아니다
     * @throws RuntimeException <b>성공한 조회가 하나도 없을 때</b> 던진다 — 원래 예외를 그대로 올린다.
     *                          삼키는 것은 {@code StockService}이고, 그래야 브레이커가 실패를 먼저 센다
     */
    @Override
    // ⚠️ Optional을 돌려주던 때가 있었다. 빈 Optional은 스프링이 null로 벗겨 캐시에 못 담고(unless 없이는
    //    IllegalArgumentException으로 튀기까지 했다 — 실물 감사 2026-08-28), 전망 없는 종목(ETF 전부)마다
    //    조회가 KIS 간격 1초를 다시 썼다. 지금은 빈 값 **객체**를 돌려 그것도 12시간 담는다
    @Cacheable(cacheNames = CacheNames.KIS_OUTLOOK, key = "#code", unless = "#result == null")
    @CircuitBreaker(name = "kisOutlook")
    public StockOutlook outlook(String code, boolean fund) {
        // 겹치지 않는다 — KIS 간격은 호출 **시작** 사이 1초라 겹쳐도 둘째는 1초를 기다린다. 순서대로 부르는 것과 같다
        Fetched<BigDecimal> target = fund ? Fetched.skipped() : Fetched.attempt(() -> targetOf(code));
        Fetched<Dividend> dividend = Fetched.attempt(() -> dividendOf(code));
        // ⚠️ **성공한 것이 하나도 없을 때만 던진다** — 「안 물었다」는 성공으로 세지 않는다.
        //    ETF는 목표가를 안 묻는데 그것을 성공으로 세면 배당 실패가 가려져 정상 반환이 되고,
        //    그 순간 위의 @CircuitBreaker가 실패를 못 본다(HackerNewsApi가 실제로 그 상태였다).
        //    반대로 하나라도 받았으면 담는다 — 안 담으면 조회마다 KIS 문 2초를 다시 쓴다
        if (!target.succeeded() && !dividend.succeeded()) {
            rethrowFirstFailure(target, dividend);
        }
        // 비어도 돌려준다 — 값이라 캐시된다(ETF·ETN이 늘 그렇다).
        // 실적발표일은 어느 엔드포인트도 주지 않는다. 국내에 무료 출처가 없어 null로 남고, 화면은 그 줄을 안 적는다
        return new StockOutlook(null, target.value(), dividend.value(), StockSource.KIS, clock.instant());
    }

    /** 증권사별 최신 발표를 접은 평균 목표가 — 발표한 곳이 없으면 {@code null}. */
    private BigDecimal targetOf(String code) {
        Opinions response = kis.get(Opinions.class, OPINION_TR_ID, code + " 투자의견", uriBuilder -> uriBuilder
                .path(OPINION_PATH)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_COND_SCR_DIV_CODE", SCREEN_DIV)
                .queryParam("FID_INPUT_ISCD", code)
                .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, OPINION_LOOKBACK_DAYS))
                .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                .build());
        return InvestOpinions.averageTargetOf(rowsOf(response)).orElse(null);
    }

    /** 다음 배당 — 잡힌 것이 없으면 {@code null}. 고르는 규칙은 {@link Dividend#nextOf}. */
    private Dividend dividendOf(String code) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        Dividends response = kis.get(Dividends.class, DIVIDEND_TR_ID, code + " 배당일정", uriBuilder -> uriBuilder
                .path(DIVIDEND_PATH)
                .queryParam("CTS", "")
                .queryParam("GB1", "0")   // 배당 전체 — 결산·중간을 가르지 않는다
                .queryParam("F_DT", today.minusDays(DIVIDEND_LOOKBACK_DAYS)
                        .format(DateTimeFormatter.BASIC_ISO_DATE))
                .queryParam("T_DT", today.plusDays(DIVIDEND_LOOKAHEAD_DAYS)
                        .format(DateTimeFormatter.BASIC_ISO_DATE))
                .queryParam("SHT_CD", code)
                .queryParam("HIGH_GB", "")
                .build());
        return Dividend.nextOf(dividendRowsOf(response), today);
    }


    /**
     * 실패한 다리의 <b>예외를 그대로</b> 올린다 — 감싸지 않는다.
     *
     * <p>감싸면 타입이 사라져 브레이커의 {@code ignoreExceptions}가 안 맞고 원인 사슬도 끊긴다
     * ({@link KisThrottle.Congested}가 그 목록에 있는 이유가 「우리 문이 우리를 거절한 것을 상대
     * 장애로 세지 않는다」인데, 맨 {@code IllegalStateException}으로 바꿔 던지면 그 보호에서 빠진다).
     * 둘이 다 실패했으면 첫째를 던지고 둘째를 {@code addSuppressed}로 붙인다 — 로그에 둘 다 남는다.
     *
     * <p>부를 자리에서 「성공한 것이 하나도 없다」를 이미 확인하므로 실패가 적어도 하나는 있다.
     */
    private static void rethrowFirstFailure(Fetched<?>... calls) {
        RuntimeException first = null;
        for (Fetched<?> call : calls) {
            RuntimeException failure = call.failure();
            if (failure == null || failure == first) {
                // ⚠️ 같은 인스턴스를 두 번 담으면 addSuppressed가 IllegalArgumentException을 던진다.
                //    지금은 던지는 자리마다 새로 할당해 안 일어나지만, 가변인자라 (x, x)를 부르기 쉽다
                continue;
            }
            if (first == null) {
                first = failure;
            } else {
                first.addSuppressed(failure);
            }
        }
        if (first != null) {
            throw first;
        }
    }


    private static List<InvestOpinions.Opinion> rowsOf(Opinions response) {
        if (response.output() == null) {
            return List.of();
        }
        return response.output().stream()
                .filter(Objects::nonNull)
                .map(row -> new InvestOpinions.Opinion(
                        row.broker(), row.date(), row.targetPrice()))
                .toList();
    }

    private static List<Dividend> dividendRowsOf(Dividends response) {
        if (response.output1() == null) {
            return List.of();
        }
        return response.output1().stream()
                .filter(Objects::nonNull)
                .map(row -> Dividend.row(
                        date(row.recordDate(), COMPACT, "record_date"),
                        date(row.payDate(), SLASHED, "divi_pay_dt"),
                        number(row.amount())))
                .toList();
    }

    /**
     * 한 칸을 날짜로 — 공백은 「아직 없다」이고, <b>모양이 어긋나면 그 칸만 {@code null}</b>이다.
     *
     * <p>⚠️ <b>던지지 않는다.</b> 던지던 때가 있었고 근거는 「조용히 {@code null}이 되면 신고가
     * 들어와도 단서가 없다」였는데, 그 근거가 실제로는 <b>성립하지 않았다</b>: 파싱은 {@code KisCall.get()}가
     * 돌아온 뒤라 {@code [kis]} 로그가 한 줄도 안 남고, 남는 한 줄은 {@code FailureReason}을 지나며
     * <b>메시지를 버려</b> 어느 필드의 어떤 값이었는지가 사라졌다.
     *
     * <p>그리고 대가가 컸다. 같은 입력이면 <b>영원히 같은 실패</b>인데 예외는 캐시되지 않으므로
     * 조회마다 되풀이되고, 캐시가 브레이커 <b>밖</b>이라 매번 새로 세어져 <b>최근 10회 중 5회</b>면
     * {@code kisStock}이 열린다 — 그 순간 <b>국내 시세가 전일 종가로 강등되고 미국 시세가 빈손</b>이
     * 된다. {@code KisStockApi$Unsupported}를 {@code ignoreExceptions}에 넣은 이유와 같은 부류다.
     * 게다가 {@code nextOf}가 버릴 <b>지난 행</b> 하나가 깨져도 답 전체가 죽었다.
     *
     * <p>지금은 <b>그 칸만 비우고 WARN에 필드 이름과 원문을 남긴다</b> — 날씨가
     * {@code SkyCondition.UNKNOWN}에서 세운 「모르는 어휘에 거부권을 주지 않는다」와 같은 자리다.
     * 단서는 오히려 늘었다: 예외 이름 하나가 아니라 <b>값</b>이 로그에 남는다.
     */
    private static LocalDate date(String raw, DateTimeFormatter format, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), format);
        } catch (DateTimeParseException e) {
            log.warn("[kis] 배당일정의 {}를 못 읽었습니다 — 그 줄만 빠집니다: '{}'", field, raw);
            return null;
        }
    }

    /** 배당금 한 칸 — 어긋나면 그 줄만 빠진다({@link #date}와 같은 이유). */
    private static BigDecimal number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[kis] 배당일정의 per_sto_divi_amt를 못 읽었습니다 — 그 줄만 빠집니다: '{}'", raw);
            return null;
        }
    }

    /**
     * ⚠️ 배열 이름이 {@code output}이다 — 시세 경로들의 {@code output1}·{@code output2}가 아니다
     * (실측 2026-08-21: 최상위 키가 {@code rt_cd}·{@code msg_cd}·{@code msg1}·{@code output}).
     * 이름을 잘못 적으면 {@code @JsonIgnoreProperties} 때문에 <b>오류 없이 빈 목록</b>이 되어
     * 「의견을 낸 증권사가 없다」와 구분되지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Opinions(@JsonProperty("rt_cd") String resultCode,
                    @JsonProperty("msg1") String message,
                    List<Row> output) implements KisResponse {
    }

    /**
     * 발표 한 건.
     *
     * @param date        {@code stck_bsop_date} — 발표일 {@code yyyyMMdd}
     * @param broker      {@code mbcr_name} — 증권사. 같은 곳의 옛 발표를 걷어내는 키다
     * @param targetPrice {@code hts_goal_prc} — 목표가. {@code 0}일 수 있고 그건 값이 아니다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(@JsonProperty("stck_bsop_date") String date,
               @JsonProperty("mbcr_name") String broker,
               @JsonProperty("hts_goal_prc") BigDecimal targetPrice) {
    }

    /** 배당일정 응답 — 배열 이름이 {@code output1}이다(실측 2026-09-07). 투자의견의 {@code output}과 다르다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dividends(@JsonProperty("rt_cd") String resultCode,
                     @JsonProperty("msg1") String message,
                     List<DividendRow> output1) implements KisResponse {
    }

    /**
     * 배당 한 건 — 화면이 쓰는 셋만 담는다. 셋 다 <b>문자열</b>로 온다.
     *
     * @param recordDate {@code record_date} — 기준일 {@code yyyyMMdd}
     * @param payDate    {@code divi_pay_dt} — 지급일 {@code yyyy/MM/dd}. 아직 안 정해졌으면 {@code ""}
     * @param amount     {@code per_sto_divi_amt} — 주당 배당금(원). 아직 안 정해졌으면 {@code "0"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DividendRow(@JsonProperty("record_date") String recordDate,
                       @JsonProperty("divi_pay_dt") String payDate,
                       @JsonProperty("per_sto_divi_amt") String amount) {
    }
}
