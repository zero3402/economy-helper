package io.saiden.economyhelper.infrastructure.kis;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.testsupport.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * <b>붙여 넣기가 남긴 개행 한 글자가 KIS 셋을 함께 죽인다.</b>
 *
 * <p>{@code EGW00105}(「유효하지 않은 AppSecret」, 토큰 발급 403)는 키가 틀린 것이 아닐 수 있다 —
 * 대시보드에 개행이 붙은 값을 넣으면 <b>환율·국내 주식·미국 주식의 1순위가 한꺼번에</b> 죽는다.
 * 그래서 코드가 <b>끝의 줄바꿈</b>을 뗀다({@code TelegramWebhookController}가 웹훅 secret에 하는 것과 같다).
 */
class KisCredentialsTest {

    @Test
    @DisplayName("헤더에 실리는 앱키·앱시크릿에서 개행을 뗀다 — 헤더가 깨지면 조회가 통째로 실패한다")
    void trimsCredentialsBeforePuttingThemInHeaders() {
        HttpHeaders headers = new HttpHeaders();

        kisHeaders("key-with-newline\n", "secret-with-spaces  \n")
                .of("token", "TR0001").accept(headers);

        assertThat(headers.getFirst("appkey")).isEqualTo("key-with-newline");
        assertThat(headers.getFirst("appsecret")).isEqualTo("secret-with-spaces");
        assertThat(headers.getFirst("authorization")).isEqualTo("Bearer token");
    }

    @Test
    @DisplayName("키가 없으면 빈 문자열이다 — null로 두면 헤더 설정에서 터진다")
    void treatsMissingCredentialsAsEmpty() {
        HttpHeaders headers = new HttpHeaders();

        kisHeaders(null, null).of("token", "TR0001").accept(headers);

        assertThat(headers.getFirst("appkey")).isEmpty();
        assertThat(headers.getFirst("appsecret")).isEmpty();
    }

    private static KisHeaders kisHeaders(String appKey, String appSecret) {
        return new KisHeaders(TestProperties.builder()
                .kisCredentials(appKey, appSecret).build());
    }
}
