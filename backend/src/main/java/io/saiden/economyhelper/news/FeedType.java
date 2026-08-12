package io.saiden.economyhelper.news;

/** 피드 형식. 어느 파서를 쓸지 고르는 기준이다. */
public enum FeedType {

    /** 표준 RSS 2.0 — 매체가 직접 내려주는 피드. */
    RSS,

    /**
     * Google News 검색 피드. 공식 RSS가 없는 매체를 대신 긁을 때 쓴다.
     * 표준 RSS 2.0이지만 제목·description에 Google이 손을 대므로 후처리가 필요하다.
     */
    GOOGLE_NEWS
}
