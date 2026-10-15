package io.github.vstory.notifyguard.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 说明文字的富文本切分与数值格式化（首页口径说明靠 `**重点**` 拉层次）。
 *
 * 锁两件容易出事的地方：标记必须成对才生效、落单的标记不许把用户该读的字吞掉。
 */
class ScreenPartsTest {

    @Test
    fun markedSpansAreBoldAndTheRestIsPlain() {
        assertEquals(
            listOf(
                RichPart("全部时间", true),
                RichPart(" · ", false),
                RichPart("模块端累计", true),
                RichPart("，清空记录即归零", false),
            ),
            richParts("**全部时间** · **模块端累计**，清空记录即归零"),
        )
    }

    @Test
    fun unmatchedMarkerIsKeptVerbatim() {
        assertEquals(listOf(RichPart("**未闭合的标记", false)), richParts("**未闭合的标记"))
        assertEquals(listOf(RichPart("****", false), RichPart("后文", false)), richParts("****后文"))
    }

    @Test
    fun plainTextStaysOneSegment() {
        assertEquals(listOf(RichPart("没有标记", false)), richParts("没有标记"))
        assertEquals(emptyList<RichPart>(), richParts(""))
    }

    @Test
    fun countsUseThousandsSeparators() {
        assertEquals("81,496", countText(81_496L, Locale.US))
        assertEquals("709", countText(709L, Locale.US))
        assertEquals("0", countText(0L, Locale.US))
    }
}
