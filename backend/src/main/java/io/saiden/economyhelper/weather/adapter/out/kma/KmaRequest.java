package io.saiden.economyhelper.weather.adapter.out.kma;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.shared.support.FailureReason;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 기상청에 <b>묻는 방법</b> — 단기예보 클라이언트({@code KmaVillageApi})가 쓴다.
 *
 * <p>{@code stock.adapter.out.datago.DataGoRequest}와 같은 자리이고, 그쪽이 주식에서 세운 규칙 둘이
 * 여기 그대로 통한다. 코드를 합치지 않는 이유는 파라미터가 겹치지 않기 때문이다 —
 * 저쪽은 {@code basDt}·{@code resultType}이고 이쪽은 {@code base_date}·{@code base_time}·
 * {@code nx}·{@code ny}·{@code dataType}이다.
 *
 * <p>⚠️ <b>서비스키를 다시 인코딩하지 않는다.</b> 발급된 키는 이미 URL 인코딩된 형태(`%` 포함,
 * 실측 길이 96)라 {@code queryParam()}이나 {@code --data-urlencode}처럼 한 번 더 인코딩하면
 * <b>{@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}</b>(「등록되지 않은 서비스키」)가 온다.
 * 그것이 <b>「활용신청이 안 됐다」로 읽힌다</b> — 실측(2026-08-26) 키를 그대로 실으니 같은 키로
 * {@code resultCode=00}이 왔다. 그래서 URI를 문자열로 직접 조립한다.
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
     * 「자료 없음」({@code NO_DATA}) — 봉투는 정상이고 {@code body}만 없다. 실측(2026-10-14 10:23)에서
     * 08시 발표가 격자 62,122·62,123·62,126에는 이것을 주고 61,126에는 정상 응답을 줬다.
     * 호출부({@code KmaVillageApi})가 한 판 앞 발표로 한 번 더 묻는 근거라 타입을 따로 둔다.
     */
    private static final String NO_DATA = "03";

    /**
     * 남한의 시간대는 하나다. 발표시각도 이 달력으로 고른다 — <b>출처가 한국이다.</b>
     * 패키지가 함께 쓴다({@code KmaVillageApi}·{@code KmaWeatherClient}).
     */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private KmaRequest() {
    }

    /**
     * 조회 URI — <b>서비스키만 그대로, 나머지는 우리가 인코딩해서.</b>
     *
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
     * <p>메시지에 {@link FailureReason}(HTTP 상태·타임아웃 등)을 싣고 원인도 남긴다 — 한동안 원인 없는
     * 「조회 실패」만 남아, 알람에서 지역이 빠져도 무엇이 터졌는지 로그가 말하지 못했다.
     * 원인은 <b>키를 지운 사본</b>이다({@link #keyless}).
     *
     * @return 역직렬화한 응답. 본문이 없으면 {@code null} — 「없음」은 호출부가 읽는다
     * @throws IllegalStateException 조회 실패. 이유와 키를 지운 원인이 남는다
     */
    static <T> T fetch(RestClient restClient, String uri, Class<T> type, String what) {
        try {
            return restClient.get().uri(URI.create(uri)).retrieve().body(type);
        } catch (RuntimeException e) {
            String reason = FailureReason.of(e);
            log.warn("[weather] 기상청 {} 조회 실패: {}", what, reason);
            throw new IllegalStateException("기상청 " + what + " 조회 실패: " + reason, keyless(e));
        }
    }

    /**
     * 원인을 <b>메시지 없이</b> 옮긴다 — 타입과 상태 코드만 남긴다.
     *
     * <p>⚠️ {@code ResourceAccessException}의 메시지에는 {@code serviceKey}가 박힌 URL이 그대로 들어가고
     * ({@code DefaultRestClient}), 4xx·5xx 메시지에는 상대가 보낸 본문이 들어간다. 그래서 원본을 원인으로
     * 달지 않는다. {@link FailureReason}이 읽는 것(상태 코드·{@code IOException}의 타입)은 그대로 남는다.
     *
     * @return 옮길 것이 없으면 {@code null} — 무엇이었는지는 메시지가 이미 말한다
     */
    private static RuntimeException keyless(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            return new RestClientResponseException("HTTP " + response.getStatusCode().value(),
                    response.getStatusCode(), response.getStatusText(), null, null, null);
        }
        IOException io = ioCauseOf(e);
        if (e instanceof ResourceAccessException) {
            return new ResourceAccessException("I/O 실패", io);
        }
        return io == null ? null : new RestClientException("본문 읽기 실패", io);
    }

    /** 사슬에서 처음 만나는 {@code IOException} — JDK 클라이언트의 것이라 URL을 싣지 않는다. */
    private static IOException ioCauseOf(Throwable e) {
        for (Throwable cause = e.getCause(); cause != null && cause != e; cause = cause.getCause()) {
            if (cause instanceof IOException io) {
                return io;
            }
        }
        return null;
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
     * <p>여는 것과 검사하는 것을 한 자리에 둔다 — 검사만 따로 두면 호출부가 「반드시 던진다」에
     * 기대어 곧바로 역참조한다.
     *
     * @return 정상인 봉투. 정상이 아니면 돌아오지 않는다
     * @throws IllegalStateException 봉투가 다르거나 {@code resultCode}가 정상이 아닐 때
     */
    static KmaVillageApi.Envelope opened(KmaVillageApi.Envelope envelope, String what) {
        if (envelope == null) {
            log.warn("[weather] 기상청 {} 응답 봉투가 다릅니다", what);
            throw new IllegalStateException("기상청 " + what
                    + " 응답 봉투가 다릅니다 — 서비스키를 의심할 자리입니다");
        }
        Header header = envelope.header();
        if (header != null && NO_DATA.equals(header.resultCode())) {
            // 봉투는 정상인데 자료가 없다 — 발표가 늦었거나 그 격자만 비었다. 한 판 앞을 물을 수 있는 실패다
            log.warn("[weather] 기상청 {} 발표에 자료가 없습니다: {} {}", what, header.resultCode(), header.resultMsg());
            throw new NoData("기상청 " + what + " 발표에 자료가 없습니다: "
                    + header.resultCode() + " " + header.resultMsg());
        }
        if (header == null || !OK.equals(header.resultCode())) {
            // HTTP는 200이라 FailureReason이 타입 이름밖에 못 준다 — resultCode는 여기서만 보인다
            String result = header == null ? "머리가 없습니다"
                    : header.resultCode() + " " + header.resultMsg();
            log.warn("[weather] 기상청 {} 응답이 정상이 아닙니다: {}", what, result);
            throw new IllegalStateException("기상청 " + what + " 응답이 정상이 아닙니다: " + result);
        }
        return envelope;
    }

    /**
     * 발표에 자료가 없다({@link #NO_DATA}) — 다른 실패와 달리 <b>한 판 앞 발표를 묻는 것이 답</b>이다.
     * {@code IllegalStateException}인 채로 두어 그 길을 안 타는 호출부에는 지금까지와 같은 실패다.
     */
    static final class NoData extends IllegalStateException {

        NoData(String message) {
            super(message);
        }
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
