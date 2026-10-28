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

    /**
     * 推荐流全屏播放页（养号助手的主战场）。
     *
     * 与 [HOME] 的区别：HOME 是顶部带「精选/团购/商城」的卡片流，FEED 是
     * **整屏一条内容 + 右侧竖排按钮栏**（点赞/评论/收藏/分享）。判定靠右侧那排按钮的
     * `content-desc`（实测 40.8.0：「未点赞，喜欢13.3万，按钮」），卡片流里没有这排东西。
     */
    FEED,
    ANOMALY,
    UNKNOWN;

    companion object {
        fun fromName(name: String): PageState =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}
