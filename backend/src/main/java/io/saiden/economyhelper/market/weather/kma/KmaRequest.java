package io.saiden.economyhelper.market.weather.kma;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 기상청에 <b>묻는 방법</b> — 단기예보 클라이언트({@code KmaVillageApi})가 쓴다.
 * 중기예보도 함께 쓰던 때가 있었는데 물렸다(그 이유는 {@code KmaWeatherClient} javadoc에 있다).
 *
 * <p>{@code market.data.DataGoRequest}와 같은 자리이고, 그쪽이 주식에서 세운 규칙 둘이 여기
 * 그대로 통한다. 코드를 합치지 않는 이유는 파라미터가 겹치지 않기 때문이다 —
 * 저쪽은 {@code basDt}·{@code resultType}이고 이쪽은 {@code base_date}·{@code base_time}·
 * {@code nx}·{@code ny}·{@code dataType}이다.
 *
 * <p>⚠️ <b>서비스키를 다시 인코딩하지 않는다.</b> 발급된 키는 이미 URL 인코딩된 형태(`%` 포함,
 * 실측 길이 96)라 {@code queryParam()}이나 {@code --data-urlencode}처럼 한 번 더 인코딩하면
 * <b>{@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}</b>(「등록되지 않은 서비스키」)가 온다.
 * 실측(2026-08-26)에서 이것 때문에 <b>「활용신청이 안 됐다」로 잘못 읽었고</b>, 키를 그대로
 * 실으니 같은 키로 {@code resultCode=00}이 왔다. 그래서 URI를 문자열로 직접 조립한다.
 *
 * <p>⚠️ <b>예외를 그대로 흘리지 않는다.</b> {@code serviceKey}가 URL에 박혀 있고
 * {@code RestClient}의 예외 메시지에는 URL이 그대로 들어간다({@code KeximFxClient}가 세우고
 * {@code AccuFailure}가 이어받은 규칙이다). 이유만 클래스 이름으로 남긴다.
 */
final class KmaRequest {

    private static final Logger log = LoggerFactory.getLogger(KmaRequest.class);

    /** 「정상처리」. 이것이 아니면 본문에 값이 없다. */
    private static final String OK = "00";

    /**
     * 남한의 시간대는 하나다. 발표시각도 이 달력으로 고른다 — <b>출처가 한국이다.</b>
     *
     * <p>패키지가 함께 쓴다({@code KmaVillageApi}·{@code KmaWeatherClient}). 예전에는 한 클라이언트에
     * 있고 형제가 <b>그 상수를 꺼내 썼다</b> — 다른 패키지의 {@code SEOUL} 선언들은 각자 자기완결이다.
     */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private KmaRequest() {
    }

    /**
     * 조회 URI — <b>서비스키만 그대로, 나머지는 우리가 인코딩해서.</b>
     *
     * <p>이 한 줄이 두 API에 각자 있으면 한쪽에서만 인코딩 방식을 바꾸는 날 그 출처가
     * 403으로 조용히 죽는다 — {@code DataGoRequest}가 생긴 이유와 같다.
     */
    static String uri(String baseUrl, String path, String serviceKey, Map<String, String> params) {
        StringBuilder uri = new StringBuilder(baseUrl).append(path)
                .append("?serviceKey=").append(serviceKey)
                .append("&dataType=JSON");
        params.forEach((name, value) -> uri.append('&').append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return uri.toString();
    }

    /**
     * 한 번 조회한다 — <b>키를 지운 예외로 바꿔 던진다.</b>
     *
     * @return 역직렬화한 응답. 본문이 없으면 {@code null} — 「없음」은 호출부가 읽는다
     * @throws IllegalStateException 조회 실패. 이유만 남는다
     */
    static <T> T fetch(RestClient restClient, String uri, Class<T> type, String what) {
        try {
            return restClient.get().uri(URI.create(uri)).retrieve().body(type);
        } catch (RuntimeException e) {
            log.warn("[weather] 기상청 {} 조회 실패: {}", what, e.getClass().getSimpleName());
            throw new IllegalStateException("기상청 " + what + " 조회 실패");
        }
    }

    /**
     * 봉투를 열어 <b>정상인지 확인하고</b> 돌려준다.
     *
     * <p>⚠️ <b>에러는 다른 봉투로 온다.</b> 정상은 {@code {"response":{"header":…,"body":…}}}인데
     * 서비스키가 틀리면 {@code {"OpenAPI_ServiceResponse":{"cmmMsgHeader":…}}}다 —
     * <b>HTTP는 200이고</b> {@code response}가 통째로 {@code null}이다. 상태 코드만 보면
     * 성공이라, 이 검사가 없으면 그 자리에서 {@code NullPointerException}이 난다.
     * KIS가 무효 토큰을 500 본문에만 적는 것과 같은 모양이다.
     *
     * <p><b>여는 것과 검사하는 것을 한 자리에 둔 이유.</b> 예전에는 「검사하는 메서드」가 따로
     * 있었고 호출부가 <b>그것이 반드시 던진다는 것에 기대어</b> 곧바로 역참조했다 — 읽는 사람도
     * 정적 분석기도 그 자리에서 NPE를 본다. 여기서 봉투를 <b>돌려주면</b> 그 암묵이 사라진다.
     *
     * @return 정상인 봉투. 정상이 아니면 돌아오지 않는다
     * @throws IllegalStateException 봉투가 다르거나 {@code resultCode}가 정상이 아닐 때
     */
    static KmaVillageApi.Envelope opened(KmaVillageApi.Envelope envelope, String what) {
        if (envelope == null) {
            throw new IllegalStateException("기상청 " + what
                    + " 응답 봉투가 다릅니다 — 서비스키를 의심할 자리입니다");
        }
        Header header = envelope.header();
        if (header == null || !OK.equals(header.resultCode())) {
            throw new IllegalStateException("기상청 " + what + " 응답이 정상이 아닙니다: "
                    + (header == null ? "머리가 없습니다"
                            : header.resultCode() + " " + header.resultMsg()));
        }
        return envelope;
    }

    /** 응답의 머리 — {@code resultCode}가 {@link #OK}가 아니면 {@code body}에 값이 없다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Header(String resultCode, String resultMsg) {
    }

    /**
     * 응답의 숫자 — 기상청은 <b>전부 문자열로</b> 준다(정수도 그렇다).
     *
     * @return 못 읽으면 {@code null} — 「모른다」다. {@code 0}으로 세지 않는다
     */
    static BigDecimal numberOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@link #numberOf}의 정수 판 — 확률(%)이 그렇다. */
    static Integer integerOf(String value) {
        BigDecimal number = numberOf(value);
        return number == null ? null : number.intValue();
    }
}
