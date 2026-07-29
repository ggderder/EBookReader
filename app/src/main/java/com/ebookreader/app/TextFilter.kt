package com.ebookreader.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 文字过滤引擎
 *
 * 核心思路：阅读App中，正文字号是主体（出现频率最高），
 * 其他UI元素（页码、电量、菜单等）字号小且分散。
 * 用统计方法找出"正文集群"，过滤掉其余内容。
 */
object TextFilter {

    /** 收集到的文本片段 */
    data class TextFragment(
        val text: String,
        val fontSize: Float,     // 字号 px
        val bounds: Rect,        // 屏幕坐标
        val className: String    // View 类名
    )

    /** 过滤规则常量 */
    private const val TOP_BAR_HEIGHT = 80       // 顶部排除区（状态栏）
    private const val BOTTOM_BAR_HEIGHT = 120   // 底部排除区（页码/进度）
    private const val MIN_TEXT_LENGTH = 1        // 最短文字
    private const val SIZE_TOLERANCE = 3f        // 字号容差（px）
    private const val MIN_BODY_FRAGMENTS = 3     // 最少正文片段数

    /** 不相干关键词 */
    private val NOISE_PATTERNS = listOf(
        Regex("^\\d+$"),                          // 纯数字页码
        Regex("^\\d+/\\d+$"),                     // "12/345"
        Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"),     // 时间 "14:30"
        Regex("^第[\\d一二三四五六七八九十百千]+[章节回]$"), // "第三章"
        Regex("^\\d+%$"),                          // "75%"
        Regex("^[\\d.]+[%％]$"),                   // 百分比
        Regex("^[A-Z]{2,5}$"),                     // 纯大写字母
    )

    private val NOISE_KEYWORDS = listOf(
        "电量", "电池", "信号", "WiFi", "蓝牙", "设置",
        "返回", "目录", "书签", "笔记", "分享", "更多",
        "加入书架", "添加书签", "夜间", "日间", "字体",
        "下一页", "上一页", "阅读进度", "本章进度",
        "正在朗读", "已暂停", "正在播放",
    )

    // ==========================================
    // 公开 API
    // ==========================================

    /**
     * 从 AccessibilityNodeInfo 树中提取并过滤出正文文本
     * @return 按阅读顺序排列的正文文本片段
     */
    fun extractBodyText(root: AccessibilityNodeInfo, screenHeight: Int): List<TextFragment> {
        // 1. 递归收集所有可见文本节点
        val allFragments = mutableListOf<TextFragment>()
        collectTextNodes(root, allFragments)

        if (allFragments.size < MIN_BODY_FRAGMENTS) {
            // 样本太少，用宽松模式：取所有内容区文本
            return allFragments
                .filter { isInContentArea(it, screenHeight) }
                .filter { isNotNoise(it) }
                .sortedBy { it.bounds.top }  // 按位置从上到下排序
        }

        // 2. 按字号聚类，找正文集群
        val bodySize = findDominantFontSize(allFragments, screenHeight)

        // 3. 用正文字号过滤
        val bodyFragments = allFragments.filter { frag ->
            isInContentArea(frag, screenHeight) &&
            isNotNoise(frag) &&
            (bodySize == null || abs(frag.fontSize - bodySize) <= SIZE_TOLERANCE)
        }

        // 4. 按屏幕位置从上到下排序（即阅读顺序）
        return bodyFragments.sortedBy { it.bounds.top }
    }

    // ==========================================
    // 内部实现
    // ==========================================

    /** 递归遍历 AccessibilityNodeInfo 树收集文字 */
    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<TextFragment>) {
        val text = node.text?.toString()?.trim() ?: ""
        // View ID 描述也可辅助判断
        val contentDesc = node.contentDescription?.toString() ?: ""

        if (text.isNotEmpty() && text.length > MIN_TEXT_LENGTH) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val className = node.className?.toString() ?: ""
            // 用 bounds 高度估算字号（正文行高通常为字号的 1.2~1.5 倍）
            val fontSize = bounds.height().toFloat() * 0.75f

            out.add(TextFragment(text, fontSize, bounds, className))
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out)
            child.recycle()
        }
    }

    /** 找出正文区域的主导字号 */
    private fun findDominantFontSize(fragments: List<TextFragment>, screenHeight: Int): Float? {
        val contentFragments = fragments.filter { isInContentArea(it, screenHeight) }
        if (contentFragments.isEmpty()) return null

        // 按字号分组（取整数部分做直方图）
        val sizeGroups = contentFragments
            .groupBy { it.fontSize.roundToInt() }
            .filter { it.value.size >= 2 }  // 至少出现2次的字号

        if (sizeGroups.isEmpty()) return null

        // 取出现频率最高的字号（正文字号）
        val dominant = sizeGroups.maxByOrNull { it.value.size } ?: return null
        return dominant.key.toFloat()
    }

    /** 是否在内容区域（非顶部/底部UI区） */
    private fun isInContentArea(frag: TextFragment, screenHeight: Int): Boolean {
        return frag.bounds.top > TOP_BAR_HEIGHT &&
               frag.bounds.bottom < screenHeight - BOTTOM_BAR_HEIGHT
    }

    /** 是否不是干扰文字 */
    private fun isNotNoise(frag: TextFragment): Boolean {
        val t = frag.text

        // 检查正则模式
        for (pattern in NOISE_PATTERNS) {
            if (pattern.matches(t)) return false
        }

        // 检查关键词
        for (keyword in NOISE_KEYWORDS) {
            if (t.contains(keyword)) return false
        }

        return true
    }
}
