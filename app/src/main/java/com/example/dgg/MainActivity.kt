package com.example.dgg

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider

/**
 * 界面原型：按参考图还原「抖关关」的三页签配置界面。
 *
 * 当前只做视觉与交互骨架，不接无障碍服务 / 抖音跳转等真实业务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: List<TextView>
    private lateinit var indicators: List<View>
    private lateinit var pages: List<View>

    private val modeDescriptions = listOf(
        "挂机涨粉：搜索关键词用户，浏览粉丝列表互动涨粉",
        "垂直养号：按行业关键词持续互动，账号垂直度更高",
        "首页连播：从推荐流进入直播间，模拟真实浏览"
    )

    private val commentModeDescriptions = listOf(
        "启动后将自动复制直播间中的最新评论并发送",
        "使用预设好的评论内容，按发送间隔轮换发出"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindTabs()
        bindSegments()
        bindSliders()
        bindClicks()
    }

    // ==================== 页签 ====================

    private fun bindTabs() {
        tabs = listOf(
            findViewById(R.id.tab_auto),
            findViewById(R.id.tab_nurture),
            findViewById(R.id.tab_live)
        )
        indicators = listOf(
            findViewById(R.id.indicator_auto),
            findViewById(R.id.indicator_nurture),
            findViewById(R.id.indicator_live)
        )
        pages = listOf(
            findViewById(R.id.content_auto),
            findViewById(R.id.content_nurture),
            findViewById(R.id.content_live)
        )

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
        }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            tab.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.brand_red else R.color.text_primary
                )
            )
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            indicators[i].visibility = if (selected) View.VISIBLE else View.INVISIBLE
            pages[i].visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    // ==================== 分段选择器 ====================

    private fun bindSegments() {
        // 校准模式：灰底分组式
        bindSegmentGroup(
            ids = listOf(R.id.seg_manual_cal, R.id.seg_auto_cal),
            initial = 1,
            grouped = true
        )

        // 养号模式：独立胶囊式
        bindSegmentGroup(
            ids = listOf(R.id.seg_mode_idle, R.id.seg_mode_vertical, R.id.seg_mode_home),
            initial = 0,
            grouped = false
        ) { index ->
            findViewById<TextView>(R.id.tv_mode_desc).text = modeDescriptions[index]
        }

        // 直播间进入方式
        bindSegmentGroup(
            ids = listOf(R.id.seg_enter_manual, R.id.seg_enter_recommend),
            initial = 0,
            grouped = false
        )

        // 评论模式
        bindSegmentGroup(
            ids = listOf(R.id.seg_comment_copy, R.id.seg_comment_preset),
            initial = 0,
            grouped = false
        ) { index ->
            findViewById<TextView>(R.id.tv_comment_mode_desc).text =
                commentModeDescriptions[index]
        }
    }

    private fun bindSegmentGroup(
        ids: List<Int>,
        initial: Int,
        grouped: Boolean,
        onChange: ((Int) -> Unit)? = null
    ) {
        val views = ids.map { findViewById<TextView>(it) }

        fun render(selected: Int) {
            views.forEachIndexed { index, view ->
                val on = index == selected
                view.setBackgroundResource(
                    when {
                        on -> R.drawable.bg_segment_selected
                        grouped -> R.drawable.bg_segment_normal
                        else -> R.drawable.bg_chip_normal
                    }
                )
                view.setTextColor(
                    ContextCompat.getColor(this, if (on) R.color.white else R.color.text_primary)
                )
                view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                render(index)
                onChange?.invoke(index)
            }
        }

        render(initial)
        onChange?.invoke(initial)
    }

    // ==================== 操作概率滑杆 ====================

    private fun bindSliders() {
        bindPercentSlider(R.id.slider_like, R.id.tv_pct_like)
        bindPercentSlider(R.id.slider_collect, R.id.tv_pct_collect)
        bindPercentSlider(R.id.slider_comment, R.id.tv_pct_comment)
        bindPercentSlider(R.id.slider_share, R.id.tv_pct_share)
    }

    private fun bindPercentSlider(sliderId: Int, labelId: Int) {
        val slider = findViewById<Slider>(sliderId)
        val label = findViewById<TextView>(labelId)
        label.text = "${slider.value.toInt()}%"
        slider.addOnChangeListener { _, value, _ ->
            label.text = "${value.toInt()}%"
        }
    }

    // ==================== 点击事件 ====================

    private fun bindClicks() {
        findViewById<TextView>(R.id.tv_log).setOnClickListener { toast("日志") }
        findViewById<TextView>(R.id.tv_unbind).setOnClickListener { toast("解绑") }
        findViewById<TextView>(R.id.btn_start_service)
            .setOnClickListener { toast("开始服务（界面原型，未接业务）") }
        findViewById<TextView>(R.id.btn_open_douyin)
            .setOnClickListener { toast("打开抖音（界面原型，未接业务）") }
        findViewById<View>(R.id.fab_menu).setOnClickListener { toast("菜单") }

        // 「去配置」「评论配置」都跳到直播互动页
        findViewById<TextView>(R.id.tv_go_config).setOnClickListener { selectTab(2) }
        findViewById<TextView>(R.id.tv_comment_config).setOnClickListener { selectTab(2) }
    }

    /** 供 XML android:onClick 调用（权限卡片在三个页签中被复用，故不依赖 id） */
    fun onPermissionTap(view: View) {
        toast("此处跳转系统授权页（界面原型）")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
