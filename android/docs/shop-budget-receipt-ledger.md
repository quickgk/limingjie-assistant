# 购物预算、回执与对账（labyrinth.shop）

本文说明「按已识别价格和余额优化购物组合，独立复核价格、回执并对账」这条链路的持久化契约与恢复语义。
代码在 `android/app/src/main/java/com/landosol/toolbox/labyrinth/shop/`，会话接入在
`LabyrinthEntryRecognitionSession` 的 `handleShoppingFrame` / `prepareShoppingAction`。

## 1. 模块划分

| 文件 | 职责 | 不负责 |
| --- | --- | --- |
| `LabyrinthShopBudgetPlanner` | 按已核实库存与余额枚举组合，共用遗物属性与随机印记职能偏好，预留刷新费及资源确定的后续购货资金 | 不做 OCR，不写盘 |
| `LabyrinthShoppingJournal` | 以「账户 + 实际 enterId」为作用域的 JSONL 事件日志、原图 PNG 与 SHA-256 | 不判断业务对错 |
| `LabyrinthShopReceiptLedger` | 回执六字段核验、购买意图关联、ownership marker | 不写 Room |
| `LabyrinthShopBalanceLedger` | 余额锚点、刷新 prepared→dispatched/rejected→settled、严格单一来源归因 | 不决定买什么 |
| `LabyrinthShoppingModule` | 会话唯一 facade，串行化所有读写 | 不持有 Bitmap |

会话只通过 facade 访问账本；`shoppingJournal` 与 `shoppingModule` 同时注入时必须指向同一对象
（`ownsJournal`），否则自动购物直接 fail-closed。

日志读取每次校验运行清单、作用域及实际文件字节，仅在字节完全一致时复用深不可变的解析结果。相同长度或修改时间不构成缓存许可。不同 facade 共用同一目录锁和有界解析缓存；追加前撤销缓存，写入、同步及关闭成功后才返回。完整行损坏直接拒绝，中断尾部须先保存再原子截断，不回退到旧缓存。库存轮次、全类型成功购买数、遗物购买数和已购槽位从同一个已核实事件快照生成；写入或下一次查询仍重新核验文件。

## 2. 事件协议

一次购买：

```
shop.purchase_intent → shop.purchase_confirm_prepared → shop.purchase_confirm_dispatched
                     → shop.purchase_completed → shop.ownership_committed
```

一次刷新：

```
shop.refresh_prepared → shop.refresh_dispatched → shop.refresh_settled
                      ↘ shop.refresh_rejected（只在确定未执行时）
```

规则：

- **prepared 先于真实手势**。`prepareConfirmation` / `prepareRefresh` 返回 `true` 才允许调用 executor；
  返回 `false` 表示已有 prepared 或已派发，绝不重复点击。
- **未知结果不释放**。executor 抛异常、超时、返回未知状态时保留 prepared；只有明确的
  `Rejected` / `Paused` / `StaleSession` / `DryRun` 才写 `rejected`。
- **迟到事件幂等**。回执先于 `dispatched` 到达时，回执本身就是完成证据，迟到派发只做校验不追加；
  已 settled 的刷新再收到 `dispatched` 不产生新事件。
- **回执字段**（标题、商品、花费、前余额、后余额）各要两次独立 OCR 确认，且两次读数在**解析后的
  事实**（商品名、花费、前后余额）上一致——原始句子的前缀、标点差异不再清零计数。金额还须满足
  `spent > 0`、`before >= spent`、`before - spent == after`。
- **字段读取**。六块字段按参考矩形各自裁出，逐块交给 PP-OCRv4（ONNX Runtime，mobile rec）识别，
  读数直接进判定。未配置 Paddle 引擎时退回拼图路径：六块裁图放大后排进一张白底拼图、整图只
  OCR 一次，按元素中心落在哪一行归属字段；白字蓝底的标题与确认按钮先做白字黑底二值化。
  两条路径裁的都是字段自己那一行：标题只取蓝底标题条本身（再往上是被压暗的商店背景，那里的
  文字在原始帧上仍然可读，裁进来会并进标题行）。取词时去掉首尾非文字符号；标题匹配「购买完成 /
  购买完毕」，两端同样容忍花纹被读成竖线一类的噪声，但带问号的标题视为别的提示而拒绝。
- **回执弹窗不算掉线**。回执与掉线的提示框共用蓝标题条，回执的「确认」按钮还会踩中「返回标题」
  模板；只要回执标题模板有分，会话失效检测就让出，避免把整笔回执点掉。
- **确认按钮不参与核验**。按钮位置由确认矩形的几何映射决定，按钮文字只留作诊断证据；
  弹窗身份由标题证明。
- **商品名对账容字形差异**。同一个字的繁/日字形与简体写法（如 櫻/樱）视为同一个字，其余字符
  仍逐字比较：名字差一个字依然拒绝。归一只用于比较，不修改证据里的原文。
- **调试诊断**。回执六行的读数与判定（未通过原因、复核次数）随帧结果写进调试状态 `shopReceiptOcr`，
  读取中与未通过时同样记录；主读引擎名与读数一并保留，ML Kit 的影子读数（默认关闭）也在这里，
  便于逐字段对账。

## 3. 余额归因与复用

- 进入商店的钱包金额须独立双读；只有普通商品页且连续作用域、无未决交易时才可抑制重复钱包 OCR，不生成替代钱包观察。
- 已核实回执的 `after` 是实际余额锚点；连续同店可直接用于下一次规划。预计余额只参与规划，交易是否完成以回执与钱包核实为准。确认弹窗、回执与刷新仍独立核实各自金额。
- 账号、开局、商店、价格上下文切换，恢复、暂停、钱包像素变化及账本异常均撤销余额复用资格。恢复持有的幂等提交不重新授予余额信任；需新的钱包核实及未决交易对账。


- 只有「区间内严格单一、带原图证据」的财务来源才能解释余额变化；否则写 `currency.observed` 并保留
  `unattributed_delta`，不臆造战斗或事件奖励。
- 刷新不使用任何回退估价（没有 300 币兜底）。
- 未决刷新期间：
  - 读数等于刷新前余额 → 视为无变化，不写事件；
  - 读数满足 `before - after == 刷新价` 且区间干净 → 写 `shop.refresh_settled`（只写一次）；
  - 同节点同楼层的其它数值 → 写 `currency.observed_pending_refresh` 诊断事件，**不写锚点**。
    锚点会让扣费区间多一条非财务事件，这次刷新就再也无法结算；诊断保留原图与读数，不参与归因。
  - 换了节点或楼层 → 那是另一处的真实余额，仍写锚点；这次刷新在店内已无法核实。

## 4. fail-closed 与停止条件

购物等待有期限，异常出口与正常关店许可分开：

- hold 帧会让 `processFrameResult` 直接返回，通用停滞看门狗根本不会运行；期限由 `holdShopping`
  自带，不依赖页面归属；
- 「进展」只算不会被立刻撤销的节点：手势真的落地（Executed，含关闭弹窗）、回执入账、持有提交、
  以及绑定 run。**意图与 prepared 只是「准备点击」**，被确定拒绝时会被取消/撤回，因此不计入；
  否则「点不动 → 重试」的循环每 1.5 秒重置一次计时，期限永远不会到；
- 同一段无进展里连续 `SHOPPING_REJECTION_BUDGET`（3）次确定拒绝请求放弃本店：每次重试都会写一条意图
  和一张整帧 PNG（拒绝只取消意图，不删已落盘的证据），等到 180 秒会留下上百张整帧图。只统计手势
  真的被拒（`Rejected` 且不是「游戏不在前台」）；`Paused` / `StaleSession` / `DryRun` 走各自的
  等待与恢复路径，不计入这个预算；
- 每一次 hold 都属于同一段无进展；只有页面状态发生变化才换一次过渡额度，因此一次读条或一次
  弹窗退场不受影响，而持续停在 UNKNOWN（回执读不出）、停在别的页面（跨节点的未决刷新）同样计时；
- 同一段无进展持续 `SHOPPING_HOLD_TIMEOUT_MILLIS`（180 秒）→ 请求异常放弃本店，消息保留当前页面与原因。
  请求只结束自动等待，购买是否完成仍由回执与钱包核实决定；待决意图与刷新写入明确的放弃记录后，独立的非消费退出流程仅在当前页面、标题、按钮与作用域已核实时取消或退出。持有提交或实际动作尚在进行时，退出流程先等动作释放。
  请求落实前按当前时间复核期限，期间出现真实进展则撤销尚未落实的超时请求；写入失败时保留退出请求，不继续购物。
- 动作锁一直不释放时：如果卡住的是购物 hold（等回执、持有提交、确认派发、刷新 prepared）且购物
  期限已过，就释放失效动作锁并转入异常放弃流程（与通用 latch 超时同一出口），不会让挂起的退出请求永远
  等不到 flush；购买/刷新按钮本身卡住不在这四类里，仍走通用 60 秒出口并带通用诊断；
- 商店页没有产生 hold 时，通用停滞看门狗按同一期限生效。

确认页在会话重启后丢失内存里的预算证据时**不会自动确认**：确认页无法重新双读金额，用耐久意图
去点等于按过期价格下单。该状态等待到上述期限后走异常放弃流程，以放弃记录收尾，等待下一次钱包核实。

买按钮按下到确认框出现之间，游戏仍会渲染一两帧商店页；这段过渡属于同一次购买，不作废内存预算
证据。确认弹窗打开后背景商品被遮罩、价格与余额不再重读，所以遗物购买的身份核对以弹窗自己的
图标为准；商店页上仍能读到核实价格时，价格必须与意图一致。

刷新同理：点下刷新到刷新确认框出现之间同样保留刷新证据（商店页与「更新确认」都算这次刷新的
过渡页），刷新确认框与退出确认同版；反过来，刷新待确认时出现购买确认或回执弹窗即视为刷新证据
失效。

## 5. 证据与保留

- 每条财务事件保存整帧原始 PNG（不缩放、不标注）+ SHA-256；路径限定在运行目录内。
- `evidenceValid` 按 `(路径, 摘要, 长度, mtime)` 缓存结果：文件落盘后不可变，但被替换时键会变化，
  仍会重新校验。
- `pruneRuns` 在每次 `open` 时清理：一共保留最近 4 次 enter（**当前打开的那次永不删除**，因此是
  「当前 + 3 个其它运行」），且目录需静置 1 小时以上才允许删除。静置时间取目录内文件的最大
  mtime，用墙钟比较（与账本事件的会话时钟无关）。删除自底向上进行：其它节点全部删掉之后才删
  manifest，任一项删不掉就保留 manifest 并留到下一轮；目录本身删不掉时把 manifest 原样写回
  （含时间戳）。写回本身失败时目录可能真的没有 manifest，下一轮清理就看不到它了，这种残留只能
  通过 `PruneOutcome.failed` 观察——`open` 会把 failed 交给构造时的诊断回调，应用层记一条
  warning。删除函数可注入，删除失败的路径在任意平台都能测。

## 6. 开关与时钟

- `LabyrinthShopPolicy.budgetOptimizationEnabled` 默认 `false` 是给未接模块的旧调用与回放测试
  用的；生产装配注入 `shoppingModule` 后恒为 `true`，没有面向用户的开关。
- 注入的 `shopPolicy` 原样使用（构造与 `start` 都尊重它），未注入时才按模块存在与否构造默认值。
- 账本事件时间都来自会话注入的 `clock`；会话调用 `confirmDispatched(clock())`，缺省值只供
  测试与兼容调用。文件清理是唯一例外：`pruneRuns` 用墙钟与文件 mtime 比较（见第 5 节）。

## 7. 购买规则与扫描覆盖

- `randomImprintRolePriority` 同时控制组合的职能偏好和组合内下一件随机印记；空配置保留默认分组，同组按真实商品位排序。选择印记不使用此配置。
- 属性档位比较共用 `LabyrinthRelicChoicePolicy.attributePriorityGroups`；显式属性依配置顺序，空配置与未配置属性使用内置阶段、层数和焦点规则。比较层级仍为直接分数 → 4 层收益 → 15／9 层收益 → 更低成本 → 职能偏好。
- 区域 4 不购买、不刷新；区域 3 排除 3 星遗物。区域 5 只有成功结算两次刷新或可靠确认不能有效刷新后才允许 3 星进入候选。
- 刷新必须有正数的资源购货预算，且余额覆盖刷新费与预算之和。`LabyrinthShopPurchaseBudgetCatalog` 按刷新后适用阶段、有效商品资源与冻结账号折扣确定资金门槛，不预测刷新结果；价格为零不参与。
- 正常关店要求交易已结清；未知余额、身份、价格、稀有度、覆盖或刷新按钮状态不能当成库存耗尽。刷新预留挤空组合时先检查有效后续行动，必要时释放预留重新规划，并保留选择印记兜底。
- FULL 覆盖证明六个物理位置的真实商品与明确空位，使用 A/B 几何、占用列位移和两次新稳定帧；空位不是 `DISABLED` 商品。同轮去重成功购买三件任意类型商品后，SINGLE_SCREEN 可直接核实不超过三件的真实顶部位置、价格、身份、按钮与其余空位，不必反复往返。
- 无按钮时不能由默认偏移量认定顶部。全空库存须必要的向顶定位动作真正执行后，再用两次新捕获的六位空证据及稳定像素完成覆盖。
- 库存轮次按成功刷新和商店作用域划分，与 `stockEpoch` 分离；购买和弹窗导致缓存失效，不清零成功购买总数。恢复、人工接管和画面矛盾撤销单屏资格。

## 8. 测试

- JVM：`LabyrinthShopBudgetPlannerTest`、`LabyrinthShoppingPolicyIntegrationTest`、
  `LabyrinthShopBalanceLedgerTest`、`LabyrinthShopReceiptLedgerTest`、`LabyrinthShoppingJournalTest`、
  `LabyrinthShoppingModuleIntegrationTest`、`LabyrinthShoppingSessionIntegrationTest`，
  以及 `vision/` 下的解析与双读状态测试。
- 设备：`LabyrinthShopOwnershipDatabaseTest`（持有幂等）、`Migration4To5Test`（迁移数据保留）。
