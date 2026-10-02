# 渠道服与按账号选择服务器

国服有两套互相独立的游戏服务器：B 服，和各联运渠道（小米、华为、vivo 等）共用的渠道服。两者网关、资源密钥、PLATFORM-ID 都不同，账号不互通。渠道服之间只有客户端包名不同。

## 服务器表（`protocol/bilibili/GameServer.kt`）

| 存储 ID | 服务器 | 客户端包名 |
|---|---|---|
| `cn-bilibili` | 国服 Bilibili | `com.bilibili.priconne` |
| `cn-xiaomi` | 小米渠道服 | `com.bilibili.priconne.mi` |
| `cn-huawei` | 华为渠道服 | `com.bilibili.priconne.huawei` |
| `cn-vivo` | vivo 渠道服 | `com.bilibili.priconne.vivo` |
| `cn-oppo` | OPPO 渠道服 | `com.bilibili.priconne.nearme.gamecenter` |
| `cn-aligames` | 九游渠道服 | `com.bilibili.priconne.aligames` |
| `cn-4399` | 4399 渠道服 | `com.bilibili.priconne.m4399` |
| `cn-mumu` | MuMu 渠道服 | `com.bilibili.priconne.yofun.mumu` |
| `cn-tencent` | 应用宝渠道服 | `com.tencent.tmgp.bilibili.priconne` |

存储 ID 写进账号表 `serverId`，发布后不可改名。`AndroidManifest.xml` 的 `<queries>` 必须覆盖全部包名（`GameClientResolverTest` 断言）。

## 登录

| | B 服 | 渠道服 |
|---|---|---|
| 游戏服网关 | `l3-prod-all-gs-gzlj.bilibiligame.net` | `l1-prod-uo-gs-gzlj.bilibiligame.net` |
| RES-KEY | `ab00a0a6…` | `d145b290…` |
| PLATFORM-ID | `2` | `4` |
| 第一层登录 | Bilibili SDK（账号密码、验证码） | 无；账号页的「登录账号 / 密码」即 `uid` / `access_key`，直接进入游戏服 `tool/sdk_login` |

来源：cc004/autopcr `autopcr/sdk/sdkclients.py` 的 `bsdkclient` / `qsdkclient`。`sdk_login` 的字段与 B 服相同，未做改动。

- 渠道服账号永不创建 Bilibili SDK 对象；游戏服要求风险验证时直接失败，不回落到 B 服验证码（`BilibiliNativeLoginCoordinatorTest`）。
- `DEVICE-ID = md5(uid)`。
- 渠道服的 `access_key` 保存时去掉首尾空白；B 服密码原样保存。

## 选择要操作的客户端（`automation/GameClientResolver.kt`）

| 选中账号 | 结果 |
|---|---|
| 服务器已知，客户端已安装 | 操作该客户端 |
| 服务器已知，客户端未安装 | `NotInstalled`，拒绝自动化，不换到别的渠道 |
| 服务器值读不懂 | `UnknownServer`，拒绝自动化，不按已安装的包猜 |
| 没有选中账号 | 只装了一个客户端时用它；零个或多个时拒绝 |

- 无障碍后端、前台在场检测、启动与重启客户端都在每次使用时读取目标包名，切换账号即切换目标。
- 解析不出目标时，前台校验收到一个永不匹配的占位包名 `com.landosol.toolbox.no-game-client`，所有动作被拒绝。`AndroidAccessibilityActionBackend` 的目标包名是非空类型，没有「不校验前台」这一档。
- 启动 Intent 去掉 `package` 字段：带着它时 `Intent.filterEquals` 与桌面启动器建的任务根不相等，系统会在现有任务上再建一个 SplashActivity，渠道客户端的 PermissionActivity 随后永久盖住 MainActivity（黑屏）。
- 启动闸门按目标游戏包是否已在前台判断；已在前台时直接接管识别，不重发启动 Intent。无障碍记录的 Activity 名可能停留在已经关闭的 SDK 公告页，不能要求它等于 MainActivity，否则冷启动导航完成后会拒绝启动单局（2026-09-26 日志包 183318）。接管后的点击仍受页面识别与前台包校验约束。

## 账号表里的服务器

- 新增账号必须选择服务器，没有默认值。
- 读不懂的 `serverId`（新版本写入、手工改库）显示为「未知服务器（原值）」，不登录、不自动化；编辑时必须重新选择服务器并重填密码。
- 更换服务器必须重填密码，并清除该账号的 SDK 会话与游戏会话。界面与仓库写入共用 `accountServerChangeRequiresPassword()`。

## 已知限制

- 运行中编辑选中账号的服务器会改变点击目标。请在自动化停止时编辑账号。
- 4399：被顶号回到标题后，4399 SDK 会弹出「用户协议及隐私政策」原生对话框，当前没有识别，连续多局会停在会话重置阶段。
- 小米客户端冷启动时 SDK 登录会让标题页停留 10 秒以上，标题页点击间隔为 5 秒。

## 实机验证

雷电模拟器 9，1920 × 1080：小米、华为、vivo 渠道服登录、刷开局与路线执行通过；4399 可以登录。OPPO、九游、MuMu、应用宝未验证。B 服登录路径与 main 相同，只是游戏包名改为按账号解析，未用 B 服账号回归。
