package io.saiden.economyhelper.testsupport;

import io.saiden.economyhelper.translate.application.port.out.SearchTermCache;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 검색어 번역 캐시의 가짜 — 번역기 호출만 보는 테스트는 {@link #none}, 캐시 동작을 보는 테스트는 {@link InMemory}. */
public final class SearchTermCaches {

    private SearchTermCaches() {
    }

    /** 늘 미스이고 담지 않는다 — 캐시가 없는 것처럼 번역기를 매번 부른다. */
    public static SearchTermCache none() {
        return new SearchTermCache() {
            @Override
            public Optional<List<String>> find(String token) {
                return Optional.empty();
            }

            @Override
            public void put(String token, List<String> terms) {
            }
        };
    }

    /** 담긴 것을 테스트가 들여다볼 수 있는 캐시. */
    public static final class InMemory implements SearchTermCache {

        public final Map<String, List<String>> stored = new ConcurrentHashMap<>();

        @Override
        public Optional<List<String>> find(String token) {
            return Optional.ofNullable(stored.get(token));
        }

        @Override
        public void put(String token, List<String> terms) {
            stored.put(token, terms);
        }
    }
}
