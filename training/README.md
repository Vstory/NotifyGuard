# training — base 模型训练

产出模块内置的那个 base 模型。纯 Python，**全程离线**，不下载任何语料。

| 产物 | 去处 | 谁在用 |
|---|---|---|
| `model.bin`（`NSPM`，262172 字节） | `app/src/main/resources/model/model.bin` | 模块在 system_server 里打分 |
| `parity.json`（54 条 `text`/`score`） | `app/src/test/resources/model/parity.json` | `SpamModelParityTest` 逐条比对 |

## 跑

```bash
cd training
python3 -m venv .venv && .venv/bin/pip install numpy scipy scikit-learn
.venv/bin/python train.py
```

约 3 秒。手机上（aarch64 PRoot）同样跑得动 —— 语料只有几千条，没有内存问题。

叠加自采样本（列格式与 `samples/` 一致，`title,content,reason`）：

```bash
.venv/bin/python train.py --extra data/mine.csv --extra-weight 2
```

## 语料

`samples/` 下的两个 CSV 是真实手机通知导出，来源、许可、标签推导规则与噪声见
**[samples/SOURCE.md](samples/SOURCE.md)**。刷新上游语料：

```bash
./fetch_samples.sh          # 跟上游 HEAD
./fetch_samples.sh <commit> # 钉版本
```

语料变了模型就变，`parity.json` 必须重新生成。

## 为什么不用公开短信语料

此前用的是 80 万条中文垃圾短信（`hrwhisper/SpamMessage`，2016）+ Kaggle SMS Spam（2011）。
量足够大，但**域不对**：短信里的正常样本是「妈妈说周末回家吃饭」这种句子，通知里的正常样本是
「设备状态同步服务开启中」「您账户于2月3日结息人民币20.90」。训出来的模型在真机上判的是另一种文本。

这不是"效果差一点"，是**大量误杀**。把旧模型（`git show HEAD:app/src/main/resources/model/model.bin`）
放到真实通知留出集上，与只用真实通知训出的新模型对比，同一批样本、同一套特征口径，唯一变量是语料：

| 阈值 | 旧模型（短信语料） | 新模型（真实通知） |
|---|---|---|
| 0.5 | P=0.180 R=0.640 | P=0.673 R=0.787 |
| 0.8 | **P=0.204 R=0.596** | **P=0.932 R=0.461** |

更能说明问题的是分数中位数：旧模型给**负样本**（正常通知）的分数中位数是 **0.627** ——
一半以上的正常通知被判为广告。阈值往上抬也救不回来（@0.8 精确率仍只有 0.204），
因为正负样本的分数分布整个叠在一起。新模型负样本中位数 0.060。

结论：**域匹配优先于数据量**。代价是语料从 80 万条降到约 5 千条。

## 关键训练配置

`class_weight="balanced"` 不是可随手换掉的旋钮。不加时分数整体被压向 0（正样本分数中位数 0.60），
`@0.8` 召回只有 0.41，可调阈值形同虚设；加上后正样本中位数 0.85、`@0.8` 召回 0.54
（以上均为 5 折 CV 均值，差异超过标准差）。

主观取舍是**召回优先于精确率**：误拦用户一眼看得见、点一下就能纠正，漏拦没人会发现。
base 模型偏保守、靠端侧 delta 补召回。

重复推送转样本权重（上限 10）—— 同一广告反复推是常态，重复次数是有用信号。

## 一致性（改任何一处都要重跑）

`features.py` 与 `app/.../judge/SpamFeatures.kt` 是**逐位一致**的两份实现，`export.py::score`
与 `app/.../ai/SpamModel.kt::score` 同样。差分叉的代价是静默的：训练时的特征和推理时的特征一旦不同，
模型在真机上就是另一个模型的输出，且没有任何报错。

1. **改特征/打分口径 = 改两边，并重跑 `train.py` 重生成 `parity.json`。** 只改一边，CI 上的
   `SpamModelParityTest` 会红 —— 它是这条一致性的唯一可执行证据。
2. **浮点累加顺序也要一致**：两侧都按桶下标升序累加（Kotlin 用 `TreeMap`，Python 用 `sorted()`），
   否则容差 1e-6 会随机失败。

易踩的两处：

- gram 按 **UTF-16 码元**切，不是码点：Python 必须先 `encode("utf-16-le")` 再按 2 字节切。
- 归一化用**显式字符集合**，不用 `str.isspace()` / `Character.isWhitespace()`：两者判定不同。

## 已知局限

- **正样本是上游设备那个 AI 的判定结果，不是人工标注。** 整条链路是蒸馏：我们复制它的判别边界，
  连同它的误判。语料里 7.6% 的文本出现过两种判定（`标签冲突`），就是它的判定不稳定留下的痕迹 ——
  我们的模型天花板被这个噪声压着。
- **单一用户、单一时段、单一机型。** 谁推得多谁的 App 在语料里占比就高。
- base 模型 `@0.8` 召回约 0.46，会漏掉一半多广告。这是 base 的水平，靠端侧 delta（M3c 的
  `SpamDelta`）在用户标注后往上补。
- 4 个字以下的文本不进 AI 段（`Judge.MIN_AI_LEN`）。
- 语料里没有真实验证码短信，模型对「验证码……请勿泄露」会给高分 —— 模块侧的硬保护词在 AI 之前拦掉这一类。
