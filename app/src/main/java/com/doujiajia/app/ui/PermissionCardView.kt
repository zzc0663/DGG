package com.doujiajia.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.doujiajia.app.R
import com.doujiajia.app.databinding.ViewPermissionCardBinding
import com.doujiajia.app.util.AppPermissions

/**
 * 权限卡片（自绘组件）。
 *
 * 之所以做成自定义 View 而不是用 `<include>`：这张卡在三个页签各出现一次，
 * 用 include 会让内部 id 在同一视图树里重复，`findViewById` 只能命中第一个，
 * 于是第二、第三个页签的状态永远刷新不了。做成自绘组件后每个实例各自持有一份
 * binding，互不干扰——这也正是 M1 里需要多处复用时唯一不会踩坑的写法。
 */
class PermissionCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Kind { ACCESSIBILITY, OVERLAY, NOTIFICATION }

    // AGP 9 的 ViewBinding 只生成 inflate(inflater) 与 inflate(inflater, parent, attach) 两个重载，
    // 没有 (inflater, parent) 的 2 参版本，故这里显式传 attachToParent = true 完成挂载。
    private val binding =
        ViewPermissionCardBinding.inflate(LayoutInflater.from(context), this, true)

    var onPermissionClick: ((Kind) -> Unit)? = null
    var onKeepAliveClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_card)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val padV = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad, padV, pad, padV)

        binding.permAccessibility.setOnClickListener { onPermissionClick?.invoke(Kind.ACCESSIBILITY) }
        binding.permOverlay.setOnClickListener { onPermissionClick?.invoke(Kind.OVERLAY) }
        binding.permNotification.setOnClickListener { onPermissionClick?.invoke(Kind.NOTIFICATION) }
        binding.tvKeepAlive.setOnClickListener { onKeepAliveClick?.invoke() }
    }

    /**
     * 用系统实时状态渲染，不做本地缓存。
     *
     * 只改颜色不改文案：三个点后面永远是无障碍 / 悬浮窗 / 通知，
     * 状态完全由颜色表达（绿=已授权、红=没授权），文字只在缺权限时变红当提醒。
     * 早先是「已授权 / 去授权」两段文字 + 一个按钮，一行 48dp、三行一百多 dp，
     * 把「开始服务」挤到屏幕很下面，而这三个权限真正要给的信息只有一个布尔值。
     */
    fun render(state: AppPermissions.State) {
        renderItem(state.accessibility, binding.tvAccessibilityStatus, binding.dotAccessibility)
        renderItem(state.overlay, binding.tvOverlayStatus, binding.dotOverlay)
        renderItem(state.notification, binding.tvNotificationStatus, binding.dotNotification)
    }

    private fun renderItem(granted: Boolean, label: TextView, dot: android.view.View) {
        dot.setBackgroundResource(
            if (granted) R.drawable.bg_dot_green else R.drawable.bg_dot_red
        )
        label.setTextColor(
            ContextCompat.getColor(
                context,
                if (granted) R.color.text_secondary else R.color.brand_red
            )
        )
        // 无障碍读屏时念得出状态：颜色对色弱用户不友好，也读不出来
        dot.contentDescription = if (granted) "已授权" else "未授权"
    }
}
