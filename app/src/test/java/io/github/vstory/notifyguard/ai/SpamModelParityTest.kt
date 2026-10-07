package io.github.vstory.notifyguard.ai

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两端打分一致性：读 `ml/parity.json`（由 `ml/train.py` 生成），逐条比 Kotlin 打分。
 *
 * 这是「Python 与 Kotlin 的特征/打分口径一致」的唯一可执行证据。改了 `features.py`
 * 或 `SpamFeatures.kt` 而没重跑 `train.py`，这里就会红——不要改容差，去改实现。
 */
class SpamModelParityTest {

    private val tolerance = 1e-6

    @Test
    fun kotlinMatchesPythonScores() {
        val model = SpamModel.bundled()
        assertNotNull("classpath 里没有 ${SpamModel.RESOURCE}（跑 ml/train.py 生成）", model)
        val rows = parityRows()
        assertTrue("parity.json 至少要 50 条，现有 ${rows.size}", rows.size >= 50)
        for ((text, expected) in rows) {
            assertEquals("「$text」的两端打分不一致", expected, model!!.score(text), tolerance)
        }
    }

    private fun parityRows(): List<Pair<String, Double>> {
        val json = javaClass.classLoader?.getResourceAsStream("model/parity.json")?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
        assertNotNull("classpath 里没有 model/parity.json（跑 ml/train.py 生成）", json)
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("text") to o.getDouble("score")
        }
    }
}
