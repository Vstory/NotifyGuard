package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.core.ModuleLogger

/**
 * 判定侧当前用的打分器。
 *
 * 判定在 system_server 的通知入队热路径上，读的是一次 volatile 快照——**绝不能在判定里做 IO**。
 * 因此加载是显式的一次性动作（[loadBundled]，装配期调用），且**失败也缓存**：模型缺失/损坏时
 * 不该每条通知都重试一遍读文件。
 *
 * M3 的端侧 delta 经 [current] 原子换入（先落盘、再换引用）。
 */
object ModelHolder {

    @Volatile
    var current: SpamScorer? = null
        private set

    /**
     * 不可变的内置 base。delta 每次都叠在**它**之上，而不是叠在 [current] 上 ——
     * 后者是「上一份 delta 的结果」，链式叠加会让「清空标注回到纯 base」变成不可能。
     */
    @Volatile
    var base: SpamModel? = null
        private set

    private var loaded = false

    fun loadBundled() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val model = SpamModel.bundled()
            if (model == null) {
                ModuleLogger.error("model.unavailable", "resource=${SpamModel.RESOURCE}", "action=pass")
            } else {
                ModuleLogger.info(
                    "model.ready",
                    "buckets=${model.buckets}",
                    "gram=${model.ngramMin}-${model.ngramMax}",
                    "fp=${model.fingerprintHex()}",
                )
            }
            base = model
            current = model
        }
    }

    /** 单测/自学习换模型用；生产路径上是 [loadBundled]。 */
    internal fun replace(scorer: SpamScorer?) {
        current = scorer
    }
}
