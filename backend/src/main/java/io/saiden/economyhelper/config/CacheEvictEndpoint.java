package io.saiden.economyhelper.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 캐시 <b>키 하나</b>를 버린다 — 배포처에 {@code redis-cli}가 없어서 만들었다.
 *
 * <p>판 번호({@link CacheNames})가 정공법이지만 그건 배포가 필요하다. 이미 나간 판에서
 * 항목 하나가 썩었을 때 쓸 수단이 따로 있어야 한다 — 그 사고는 {@code CLAUDE.md} 참고.
 *
 * <p><b>Boot가 주는 {@code /actuator/caches}를 대신하지 않고 <i>보탠다</i>.</b> 그쪽은 이름
 * 목록과 <b>캐시 통째로 비우기</b>까지 해 주는데 <b>키 하나를 지목하지 못한다</b>. 우리가
 * 버려야 하는 것은 대개 항목 하나({@code geocode-v2::성남시|KR})이고, 통째로 비우면 멀쩡한
 * 나머지까지 무료 티어 쿼터를 다시 태워 채워야 한다. 그래서 <b>없는 것만</b> 만든다 —
 * 목록과 전체 비우기는 그쪽을 쓴다.
 *
 * <p>⚠️ 처음에 id를 {@code caches}로 잡았더니 컨텍스트가 통째로 안 떴다:
 * {@code Found two endpoints with the id 'caches'}. Boot의 {@code CachesEndpoint}는
 * {@code CacheManager}가 있으면 노출 여부와 무관하게 <b>등록</b>되므로 이름이 부딪힌다.
 * 스프링 컨텍스트 테스트가 그걸 잡았다.
 *
 * <p><b>일반 컨트롤러가 아니라 액추에이터 엔드포인트인 이유는 {@code DigestEndpoint}와 같다 —
 * 포트를 분리하기 위해서다.</b> 캐시를 비우는 것은 쿼터를 태우는 일이다(FMP 하루 250회 ·
 * AccuWeather 50회 · 수출입은행 1,000회가 전부 캐시를 실질 방어로 삼는다). 애플리케이션
 * 포트(8080)에 있으면 외부 누구나 그 방어를 벗길 수 있다. {@code management.server.port}로
 * 8081에 격리하고, 포트를 하나로 합치는 배포처에서는 {@code digest}와 함께 노출에서 빼는 것이
 * 방어다({@code DEPLOY.md} 참고).
 *
 * <pre>
 * curl localhost:8081/actuator/caches                      # 이름 목록 (Boot 것)
 * curl -X DELETE 'localhost:8081/actuator/evict?name=geocode-v2&key=성남시|KR'
 * </pre>
 */
@Component
@Endpoint(id = "evict")
public class CacheEvictEndpoint {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictEndpoint.class);

    private final CacheManager cacheManager;

    public CacheEvictEndpoint(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 키 하나를 버린다.
     *
     * <p><b>캐시를 통째로 비우지 않는다.</b> 그건 Boot의 {@code DELETE /actuator/caches}가
     * 하고, 여기서 겸하면 {@code key}를 빠뜨린 호출이 전체 삭제가 되어 버린다 — 되돌릴 수
     * 없고 다음 조회가 쿼터를 태우는 일을 오타 하나로 일으키게 하지 않는다.
     *
     * @param name {@link CacheNames}의 값. <b>판 번호까지</b> 적어야 한다({@code geocode-v2})
     * @param key  {@code @Cacheable}이 만든 키 그대로({@code 성남시|KR})
     * @return 무엇을 했는지. 이름이나 키가 어긋나도 예외를 내지 않고 이유를 담아 돌려준다 —
     *         진단하러 부르는 자리에서 500을 받으면 이름이 틀린 것인지 앱이 죽은 것인지 모른다
     */
    @DeleteOperation
    public Map<String, Object> evict(String name, String key) {
        if (name == null || name.isBlank() || key == null || key.isBlank()) {
            return Map.of("evicted", false,
                    "reason", "name과 key를 모두 주십시오 — 캐시를 통째로 비우는 것은 "
                            + "DELETE /actuator/caches가 합니다");
        }
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            log.warn("[cache] 없는 캐시를 비우려 했습니다: {} (등록된 것: {})",
                    name, cacheManager.getCacheNames());
            return Map.of("evicted", false, "cache", name,
                    "reason", "그런 이름의 캐시가 없습니다 — 판 번호까지 적었는지 보십시오");
        }
        cache.evict(key);
        log.info("[cache] '{}'에서 키 '{}'를 버렸습니다", name, key);
        return Map.of("evicted", true, "cache", name, "key", key);
    }
}
