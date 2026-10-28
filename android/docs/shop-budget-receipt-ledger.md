# 购物预算、回执与对账（labyrinth.shop）

本文说明「按已识别价格和余额优化购物组合，独立复核价格、回执并对账」这条链路的持久化契约与恢复语义。
代码在 `android/app/src/main/java/com/landosol/toolbox/labyrinth/shop/`，会话接入在
`LabyrinthEntryRecognitionSession` 的 `handleShoppingFrame` / `prepareShoppingAction`。

## 1. 模块划分

| 文件 | 职责 | 不负责 |
| --- | --- | --- |
| `LabyrinthShopBudgetPlanner` | 用已识别且双读核实的库存与余额枚举组合，预留刷新费与最便宜商品的周转金额 | 不做 OCR，不写盘 |
| `LabyrinthShoppingJournal` | 以「账户 + 实际 enterId」为作用域的 JSONL 事件日志、原图 PNG 与 SHA-256 | 不判断业务对错 |
| `LabyrinthShopReceiptLedger` | 回执六字段核验、购买意图关联、ownership marker | 不写 Room |
| `LabyrinthShopBalanceLedger` | 余额锚点、刷新 prepared→dispatched/rejected→settled、严格单一来源归因 | 不决定买什么 |
| `LabyrinthShoppingModule` | 会话唯一 facade，串行化所有读写 | 不持有 Bitmap |

会话只通过 facade 访问账本；`shoppingJournal` 与 `shoppingModule` 同时注入时必须指向同一对象
（`ownsJournal`），否则自动购物直接 fail-closed。

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
- **回执六字段**（标题、商品、花费、前余额、后余额、按钮）都要两次独立 OCR 确认，且
  `spent > 0`、`before >= spent`、`before - spent == after`。

## 3. 余额归因

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

购物不盲点、也不允许靠退出兜底，因此它的等待必须有期限：

- hold 帧会让 `processFrameResult` 直接返回，通用停滞看门狗根本不会运行；期限由 `holdShopping`
  自带，不依赖页面归属；
- 「进展」只算不会被立刻撤销的节点：手势真的落地（Executed，含关闭弹窗）、回执入账、持有提交、
  以及绑定 run。**意图与 prepared 只是「准备点击」**，被确定拒绝时会被取消/撤回，因此不计入；
  否则「点不动 → 重试」的循环每 1.5 秒重置一次计时，期限永远不会到；
- 同一段无进展里连续 `SHOPPING_REJECTION_BUDGET`（3）次确定拒绝直接停止：每次重试都会写一条意图
  和一张整帧 PNG（拒绝只取消意图，不删已落盘的证据），等到 180 秒会留下上百张整帧图。只统计手势
  真的被拒（`Rejected` 且不是「游戏不在前台」）；`Paused` / `StaleSession` / `DryRun` 走各自的
  等待与恢复路径，不计入这个预算；
- 每一次 hold 都属于同一段无进展；只有页面状态发生变化才换一次过渡额度，因此一次读条或一次
  弹窗退场不受影响，而持续停在 UNKNOWN（回执读不出）、停在别的页面（跨节点的未决刷新）同样计时；
- 同一段无进展持续 `SHOPPING_HOLD_TIMEOUT_MILLIS`（180 秒）→ 停止整局，消息带当前页面与原因。
  到期时如果动作锁仍被占着（例如手势被反复拒绝），停止请求会等动作释放后再执行，执行前按当前
  时间复核期限：期间有真实进展就撤销该请求；
- 动作锁一直不释放时：如果卡住的是购物 hold（等回执、持有提交、确认派发、刷新 prepared）且购物
  期限已过，就释放锁并按购物超期停止（与通用 latch 超时同一出口），不会让挂起的停止请求永远
  等不到 flush；购买/刷新按钮本身卡住不在这四类里，仍走通用 60 秒出口并带通用诊断；
- 商店页没有产生 hold 时，通用停滞看门狗按同一期限生效。

确认页在会话重启后丢失内存里的预算证据时**不会自动确认**：确认页无法重新双读金额，用耐久意图
去点等于按过期价格下单。该状态会一直 hold 到上面的期限，然后停止并留下诊断，需要人工处理。

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

## 7. 测试

- JVM：`LabyrinthShopBudgetPlannerTest`、`LabyrinthShoppingPolicyIntegrationTest`、
  `LabyrinthShopBalanceLedgerTest`、`LabyrinthShopReceiptLedgerTest`、`LabyrinthShoppingJournalTest`、
  `LabyrinthShoppingModuleIntegrationTest`、`LabyrinthShoppingSessionIntegrationTest`，
  以及 `vision/` 下的解析与双读状态测试。
- 设备：`LabyrinthShopOwnershipDatabaseTest`（持有幂等）、`Migration4To5Test`（迁移数据保留）。
