# ml — base 模型训练

产出两个东西：

| 产物 | 去处 | 谁在用 |
|---|---|---|
| `model.bin`（`NSPM`，约 262 KB） | `app/src/main/resources/model/model.bin` | 模块在 system_server 里打分 |
| `parity.json`（≥50 条 `text`/`score`） | `app/src/test/resources/model/parity.json` | `SpamModelParityTest` 逐条比对 |

## 跑

```bash
cd ml
uv run train.py                                   # 首次会下载公开语料到 ml/data/（已 gitignore）
uv run train.py --limit 100000                    # 手机上控内存，见下
uv run train.py --extra data/mine.csv             # 掺入自采真实通知（正样本权重默认 ×10）
```

手机上（aarch64 PRoot）跑：向量化用 `array` 累积，但仍会吃内存，`--limit` 建议 ≤100000。
没有 `uv` 时用 venv 等价：

```bash
python3 -m venv /tmp/ngml-venv && /tmp/ngml-venv/bin/pip install numpy scipy scikit-learn
/tmp/ngml-venv/bin/python train.py --limit 100000
```

## 一致性（改任何一处都要重跑）

`features.py` 与 `judge/SpamFeatures.kt` 是**逐位一致**的两份实现，`export.py::score`
与 `ai/SpamModel.kt::score` 同样。差分叉的代价是静默的：训练时的特征和推理时的特征一旦不同，
模型在真机上就是另一个模型的输出，且没有任何报错。

所以有两条硬约束：

1. **改特征/打分口径 = 改两边，并重跑 `train.py` 重生成 `parity.json`。** 只改一边，CI 上的
   `SpamModelParityTest` 会红——它是这条一致性的唯一可执行证据。
2. **浮点累加顺序也要一致**：两侧都按桶下标升序累加（Kotlin 用 `TreeMap`，Python 用 `sorted()`），
   否则容差 1e-6 会随机失败。

易踩的两处：

- gram 按 **UTF-16 码元**切，不是码点：Python 必须先 `encode("utf-16-le")` 再按 2 字节切。
- 归一化用**显式字符集合**，不用 `str.isspace()` / `Character.isWhitespace()`：两者判定不同。

## 数据

公开语料只作为冷启动，和 App 通知的分布差得远（中文语料的正常样本偏句子片段）。
真实效果以自采通知样本为准：`ml/data/` 只在本机，已被 `.gitignore` 忽略，不要提交。

语料来源：

- 中文带标签短信（约 80 万条，`label\tcontent`）：`hrwhisper/SpamMessage`
- 英文 SMS Spam Collection：`mohitgupta-omg/Kaggle-SMS-Spam-Collection-Dataset-`

## 已知局限

- 短信语料里的正常样本不像真实聊天/App 通知，短问候语（「你好」）偏高分。
- 语料没有真实验证码短信，模型对「验证码……请勿泄露」会给高分——模块侧的硬保护词在 AI 之前拦掉这一类。
- 4 个字以下的文本不进 AI 段（`Judge.MIN_AI_LEN`）。
