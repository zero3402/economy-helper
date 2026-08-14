package io.saiden.economyhelper.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 자기 주소를 주기적으로 친다 — <b>무활동으로 잠드는 호스트에서 깨어 있기 위해서다.</b>
 *
 * <p><b>왜 밖에서 치는 것으로 부족한가.</b> GitHub Actions 예약 실행을 쓰고 있었는데
 * 실측 전달률이 6~11%였다(4시간 28분에 3회, 예정은 27~53회). GitHub 문서가 예약 실행을
 * "부하가 높으면 지연되거나 <b>건너뛴다</b>"고 명시한 그대로다 — 더 촘촘히 예약해도
 * 버려지는 쪽이 늘 뿐이다. 잠들면 스케줄러도 멈추므로 <b>그날 아침 브리핑이 통째로
 * 안 나가는</b> 일까지 생긴다. 우리 스케줄러는 우리가 보장할 수 있다.
 *
 * <p><b>반드시 공개 주소를 친다.</b> {@code localhost}로 치면 호스트의 라우터를 거치지 않아
 * 유휴 타이머가 리셋되지 않는다. 밖으로 나갔다 되돌아오는 요청이어야 인바운드로 잡힌다.
 *
 * <p><b>배포처를 알지 않는다.</b> 이 클래스가 아는 것은 "칠 주소 하나"뿐이고, 비어 있으면
 * 아무 일도 하지 않는다 — 항상 켜져 있는 곳으로 옮기면 그 값만 비우면 되고 코드는 그대로다.
 * {@code RENDER_EXTERNAL_URL} 같은 벤더 변수를 읽지 않는 것도 같은 이유다. 그 연결은
 * 배포처 대시보드에서 이어 준다. 스핀다운은 한 벤더만의 것이 아니다.
 *
 * <p>주기와 쉬는 시간대는 설정이 정한다. 월 가동시간 한도가 있는 곳이라면 크론에서 그
 * 시간대를 빼면 된다 — 한도라는 사실이 코드가 아니라 값에 담긴다.
 */
@Component
public class SelfPing {

    private static final Logger log = LoggerFactory.getLogger(SelfPing.class);

    private final RestClient restClient;
    private final String url;

    public SelfPing(RestClient.Builder builder,
                    @Value("${economy-helper.keep-warm.url:}") String url) {
        this.restClient = builder.build();
        // 대시보드에 붙여 넣은 값은 끝에 공백이나 줄바꿈이 붙기 쉽다
        this.url = url == null ? "" : url.trim();

        if (this.url.isBlank()) {
            log.info("[keep-warm] 자체 핑이 꺼져 있습니다 — 잠들지 않는 호스트라면 정상입니다");
        } else {
            log.info("[keep-warm] 자체 핑 대상: {}", this.url);
        }
    }

    /**
     * <p><b>실패해도 조용히 넘어간다.</b> 깨어 있는 것이 목적이라 응답 내용은 상관없고,
     * 404여도 요청이 호스트에 닿은 순간 유휴 타이머는 이미 리셋됐다. 상태 코드로 시끄러우면
     * 정작 봐야 할 로그가 묻힌다.
     */
    @Scheduled(cron = "${economy-helper.keep-warm.cron}", zone = "UTC")
    public void ping() {
        if (url.isBlank()) {
            return;
        }
        try {
            restClient.get().uri(url)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> { })
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // 닿지도 못한 경우다. 이건 남긴다 — 주소가 틀렸거나 호스트가 죽은 것이다
            log.warn("[keep-warm] 자체 핑 실패: {}", e.toString());
        }
    }
}
