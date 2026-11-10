---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 3144a37a9207ea4301a32a24b64ea984_9828f14cdcc611f1bc42525400638852
    ReservedCode1: ZXzc/Ca1/kCcRCk2iTh2wpqa01KqpD0sV6eKJaA0jSv3+/a1uwvec96mwsqxrBM8u9b+fc8mEs50jwbfOWxFXwF0X9FH4vAbAP21jcYO/xEHQ3p2/yGWxq49K0OBB3YI2cT6IlHweoyeA0aYiVTrdSne5/SmO6X4h0CzKXRpcLdbhksZ0bu+PDiCYNw=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 3144a37a9207ea4301a32a24b64ea984_9828f14cdcc611f1bc42525400638852
    ReservedCode2: ZXzc/Ca1/kCcRCk2iTh2wpqa01KqpD0sV6eKJaA0jSv3+/a1uwvec96mwsqxrBM8u9b+fc8mEs50jwbfOWxFXwF0X9FH4vAbAP21jcYO/xEHQ3p2/yGWxq49K0OBB3YI2cT6IlHweoyeA0aYiVTrdSne5/SmO6X4h0CzKXRpcLdbhksZ0bu+PDiCYNw=
---

复查完毕。以下是纯静态审阅结果（未跑 Gradle、未上机），覆盖 SessionClock、AiController、AiNarrator、NarrationBlock、SurgicalAudio、GazePinchController、PanelFrame、VitalsPanel、ImagingPanel、ScopePanel、SurgicalSession、ConsolePage、FocusPanel。

## 一、语法复查

**结论：未发现阻断性语法错误。** clipRect 缺失 import 已修复，括号、import、接收者作用域均闭合正常，Kotlin 层面不存在必然编译失败项。以下为可核对的隐患，按严重度排序：

| 编号 | 位置 | 问题 | 级别 |
|---|---|---|---|
| S1 | `SurgicalSession.events` + `log()` | `mutableStateListOf` 被 SessionClock 线程、AiController 的 pipeline 线程、UI 线程三路并发写。Compose 快照列表非线程安全，长时运行存在快照冲突/遍历异常（真机偶发崩溃点） | 高 |
| S2 | `PerfGovernor.reportFrame` | 被 5 个面板时钟 + HoloEdgeOverlay 每帧重复上报，警报时同一帧最多 9 次。EMA 权重被放大 5–9 倍，`avgFrameMs` 已不能当作单帧耗时读，且降级阈值会更早触发（画面反而更容易被降频） | 高 |
| S3 | `SessionClock` 主循环 | `Thread.sleep(100)` 不补偿计算耗时，节拍恒 >100ms；8 小时长时运行会累积到分钟级偏差。且 `running=false` 时 tick 仍自增，恢复瞬间 `t = elapsedSec + tick%10/10` 相位跳变，波形会抖一下 | 中 |
| S4 | `AiController.stage()` | 后台线程以 20ms 步进直写 `focusAnim`（50Hz 状态写），与 60Hz 视觉时钟叠加，FocusPanel 每帧存在两个重组来源 | 中 |
| S5 | `AiNarrator.ensureWorker()` | `poll() ?: break` 与 `worker?.isActive` 存在窗口期竞态：恰在 worker 已取空、尚未结束的瞬间 `stream()`，新行进队但无人消费 → 偶发"某条建议不显示" | 中 |
| S6 | `PerfGovernor` 恢复去抖 | 用帧数（240 帧）去抖，但帧率本身在 20/45/60 之间变化，LOW 下 240 帧 = 12s，恢复过慢；同一阈值在不同等级下语义不一致 | 中 |
| S7 | `ScopePanel` / `ImagingPanel` | `"%.2f".format()` 走默认 Locale，系统语言为德/法/俄等逗号小数点区域会渲染成 `1,20x` | 中 |
| S8 | `ScopePanel` 内 `fun bracket` | 定义在 DrawScope lambda 内的局部函数靠外层隐式接收者访问 `drawLine`，当前可编译但边界脆弱（移出 lambda 立刻报错），且每帧为 4 次调用创建闭包 | 低 |
| S9 | `PanelFrame.rememberSmoothInt` | 全仓零调用点，属死代码 | 低 |
| S10 | `VitalsPanel` 光标 | 注释写"扫掠光标"，实际 `cursorX = w * 0.999f` 是静止竖线，名不符实 | 低 |
| S11 | `PerfGovernor.attach` | BroadcastReceiver 未保存引用、无注销路径，`attached` 永真 | 低 |

## 二、性能开销

| 热点 | 现状开销 | 优化方向 | 预期收益 |
|---|---|---|---|
| 动画时钟位置 | 4 个面板在**顶层**调用 `rememberAnimatedSeconds()`，`t` 的读取点在 PanelFrame 之前 → 60Hz 整棵面板树重组（含 PanelFrame 的 Text/Row/Border/StatusDot） | 时钟下沉：提供 `State<Float>`，仅在 Canvas/draw 层读取；PanelFrame 子内容用 `derivedStateOf` 或参数化隔离 | 重组量级降一个数量级，绘制不变 |
| HoloEdgeOverlay | 每帧新建 4 个渐变 + 2 个扫描 Brush，5 窗全屏覆盖 → 每帧约 30 次大面积渐变填充（XR 一体机是实打实的填充率与带宽） | `remember(colors, edge)` 缓存 Brush + `drawWithCache`；非焦点窗降为纯色描边 | GPU 填充率显著下降，发热改善 |
| ScopePanel | 每帧 11 个 `Path`、约 300+ 段线、3 次 `Paint()` 新建、1 次全视野 radialGradient、`10.sp.toPx()` 重复换算 | 静态层（皱襞/血管/组织底）烘焙成 `ImageBitmap`，仅 zoom/荧光切换时重建；Paint 与 Brush 全部 remember；动态层只留渗血点/器械/准星 | 单帧绘制成本降约一个数量级 |
| 数值插值 | 5 个 `rememberSmoothFloat` 各起一条 `withFrameNanos` 循环 = 5 条协程 + 每帧 5 次状态写，且已收敛仍每帧 set | 合并为一条循环批量更新；`delta == 0f` 提前 return | CPU 唤醒与状态写减少 80% |
| MiniRadar 拖尾 | 每 55ms 执行 `filter`+`groupBy`+`flatMap`+新 `ArrayList`，每秒约 18 轮分配；每帧 5 次 `lastOrNull` 线性扫 | 固定 ring buffer（每窗一个 `FloatArray`），只增量失效 | 稳态零分配，避免 GC 抖动 |
| AiNarrator | 每 22ms（45 次/秒）`buffer.toList()` 全量复制并重组，行数只增不减 | 2 字符/拍（22ms×2，视觉等价）；行文本用 StringBuilder，行末才 copy；buffer 限 8 行 | 状态写与分配各减半 |
| SurgicalAudio | 15ms 常驻轮询 = 66 次/秒唤醒；`sleep(15)` 在 Android 上实际被量化到 16–20ms，心率 60 时滴声间隔有 ±5% 抖动 | 改为 sleep 到下一拍点（`max(1, next-now)`），常态唤醒降到 1–2 次/秒 | 续航与滴声节律准确性同时改善 |
| ImagingPanel | 拖切层时每层重建 `IntArray(9216)` + `Bitmap` | 复用 IntArray，缓存最近 8 层 | 拖动切层无 GC 抖动 |

## 三、动画设计：清楚但不显眼

**核心问题不是"动效不够"，而是"动的太多、太同时"。** 当前单个 Scope 窗同时有 6 个动效源（组织漂移、血管浮动、渗血点搏动、渗出环双环扩散、器械咬合、荧光辉光），叠加 HoloEdge 的 5.4Hz 呼吸 + 0.16 归一化速度双轴扫描；警报时 5 个窗同频共振。对紧张状态下的术者，这属于余光噪音，还会抢占注意力。

**五条原则**

1. **一动一静**：同一窗口任意时刻最多两个动效源，其余全部静止（静止不是"死板"，是"可读"）。
2. **降频不降信息**：所有纯装饰动效周期 ≥0.8s，禁止 3Hz 以上（手术灯下高频易引起频闪不适）。
3. **幅度阈值内**：位移 ≤2dp、透明度变化 ≤0.12、缩放 ≤1.5%，保证"余光只能感知到在活，看不清在怎么动"。
4. **紧急用颜色不用运动**：CRITICAL 时收掉扫描线、呼吸、扩散环，只保留红框 + 文字。红色已经承载注意力，再加运动就是双重刺激。
5. **缓动统一**：全部 sine / ease-in-out，禁止线性连续运动（线性在余光里最抓眼）。

**参数对照**

| 元素 | 当前 | 建议 | 理由 |
|---|---|---|---|
| 外框呼吸 | `sin(t*5.4)`，α 0.7–1.0，5 窗同亮 | 周期 1.6s，α 0.90–1.0，只亮"告警源窗"+"聚焦窗" | 5.4Hz 偏快且有频闪感；全窗同亮等于没有重点 |
| 扫描线 | 双轴 0.16/0.11 速度，常驻 | 单轴、速度 0.06、仅 gazeHot 时出现、α 0.06 | 常驻扫描是无信息运动，改为"注视反馈"才有语义 |
| 影像 ROI 脉动 | `sin(t*3.2)`，α 0.35–0.75 | 周期 1.2s，α 0.30–0.38 | 振幅过大就是"闪"，收敛后仍能读出"在跳" |
| 内窥镜渗出环 | `sin(t*4.2)` 双环 | 单环，周期 1.0s，α 0.45–0.55 | 双环 4.2Hz 是全屏最刺眼的一项 |
| 组织/血管漂移 | `t*0.5` / `t*0.3` | 降到 `t*0.18`，幅度 ×0.5 | 慢=呼吸感，快=晕眩 |
| 器械咬合 | `sin(t*0.8)*0.03r` | 保留，幅度 0.02r | 0.8Hz 接近生理节律，是当前最自然的一项，作为"基准动效"保留 |
| 雷达拖尾 | 18Hz 采样、620ms 寿命 | 12Hz 采样，寿命不变 | 采样率与视觉帧率无关，降采样无感知损失 |
| 打字光标 | 430ms 硬切 | 600ms 交叉淡入淡出（α 0.9↔0.35） | 硬切在余光里是"闪点"，淡入淡出几乎无感 |
| 告警条 | 颜色瞬变 | 300ms 颜色插值；危急时闪 2 次×200ms 后转常亮 | 持续闪违反抗警报疲劳的既定设计 |

**加一档"动效强度"开关**：静默（默认，全部降至静态+仅颜色）/ 标准 / 演示（现有表现）。比赛中这既是术中安全考量，也是可访问性加分项。

## 四、其他建议

- **A. 告警仲裁**：阻断超时（≤60s 红）目前只改颜色，不参与音频优先级；若此刻已是 CRITICAL，术者听不到"阻断即将到期"这个更急的流程告警。建议做优先级抢占（流程性时限 > 生理指标）。
- **B. 告警抑制**：允许对 NOTICE/WATCH 设 60s 静音，CRITICAL 不可静音，静音动作写事件流（可追溯）。这是真实手术室的刚需，也是答辩亮点。
- **C. 数据来源徽标**：体征数值旁加 `MONITOR / SIM` 小标。当前演示数据与真实监护在视觉上不可区分，医疗软件这是合规底线，也直接回应评审"演示数据会不会误导"的提问。
- **D. 交互两级化**：凝视 250ms 轻高亮（预选）、750ms 才可捏合确认；确认后 1.5s 内反向捏合可撤销。当前 750ms+0.34 阈值在无菌操作下误触与迟滞风险并存。
- **E. 性能 HUD**：演示模式可见的浮层，显示帧耗时、降级等级、组件重组次数。优化前后有数据可举证，比口头说"我优化了性能"有效得多。
- **F. 线程收口**：现有 4 处裸 `Thread`（SessionClock/音频/AI pipeline/demo-hold），建议统一命名线程池并绑定生命周期，避免窗口重建后残留。
- **G. 影像缓存复用**：见上表末行，同时把 96 层做成 LRU，滑动切层手感会更稳。
- **H. 确定性**：`SignalEngine.jitter` 用 sin 而非 `Random`，演示可复现——这点已经做对了，建议在文档里写明，是加分项。

## 五、建议落地顺序

| 阶段 | 内容 | 触及文件 | 红线影响 |
|---|---|---|---|
| P0 | S1 日志线程收口、S2 帧上报去重、动画时钟下沉、HoloEdge Brush 缓存 | SurgicalSession / PerfGovernor / PanelFrame / 4 个面板 | 不触碰 |
| P1 | ScopePanel 静态层烘焙、MiniRadar ring buffer、音频精确 sleep、Narrator 步进 | ScopePanel / MiniRadar / SurgicalAudio / AiNarrator | 波形降密度已由 waveStride 控制，数值与红框保持实时 |
| P2 | 动画参数按上表收敛 + 动效强度开关 + 告警仲裁与静音 | PanelFrame / ScopePanel / ImagingPanel / SurgicalAudio / SessionClock(仅音频调度) | SessionClock 100ms 心跳与判级逻辑完全不改 |

要现在动手的话，我建议从 P0 开始，改完一批给你看 diff 摘要，再进 P1。确认即开工。
*（内容由AI生成，仅供参考）*
