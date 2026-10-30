package com.doujiajia.app.data

/**
 * 「视频文案标签」的预填示例。
 *
 * 刻意**不做成 assets 文件**（与话术库 `live_comments.json` 不同）：
 * 标签是用户自己维护的数据，改一次就落进 SharedPreferences 成为用户数据；
 * 预置内容只在第一次打开编辑框时当示例，不参与运行逻辑。多一层文件加载只会多一个
 * "到底用哪份"的歧义。
 *
 * 一行一条，语法见 [com.doujiajia.app.engine.NurtureTags.parse]：
 * 词后面可以跟一个百分比作为加成，不写默认 100%。
 */
object PresetTags {

    val EXAMPLES: String = """
        健身
        减脂 60
        健身餐 60
        撸铁 50
        蛋白粉 50
        跑步 40
        减肥 60
        增肌 50
    """.trimIndent()
}
