package com.example.dgg.engine

/**
 * 页面状态。JSON 里的页签名必须能映射到这里，否则引擎会把它当成 UNKNOWN。
 * 新增页面时：先在这里加枚举，再在 assets 里加规则。
 */
enum class PageState {
    HOME,
    SEARCH,
    SEARCH_RESULT,
    USER_PROFILE,
    USER_LIST,
    LIVE_ROOM,
    ANOMALY,
    UNKNOWN;

    companion object {
        fun fromName(name: String): PageState =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}
