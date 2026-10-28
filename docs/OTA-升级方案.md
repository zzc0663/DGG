# OTA 升级方案（本地接口先行，云端后续接入）

> 2026-10-28 制定。目标：**客户端把「检查 → 下载 → 校验 → 安装」整条链路做完并可用，
> 服务端只需实现一个 JSON 接口**；服务端就绪后不改 UI、不改状态机，只替换注入的实现。

---

## 1. 先纠正一个前提：这里做不到"推送"

没有 FCM / 厂商推送通道（本应用是侧载分发，不上架商店），自建服务端无法主动唤醒客户端。
所以"OTA 推送"在客户端只能落成两种形态：

| 形态 | 可行性 | 说明 |
|---|---|---|
| **启动检查**（冷启动 + 前台恢复） | ✅ | 每次打开抖关关查一次，6 小时内不重复 |
| **定时检查**（后台每 6~12 小时） | ⚠️ | 需要后台任务；国产 ROM 会杀，只能"尽力而为" |
| 服务端主动推送 | ❌ | 无推送通道。除非客户端维持长连接（耗电 + 被 ROM 断，不划算） |

结论：**本期做「启动检查 + 手动检查」**，接口预留 `etag`（304 省流量）与 `channel`（灰度）。
真要"推送触达"，后期接厂商推送（小米/OPPO 推送需各自 SDK 与商店上架）再议，不在本期。

---

## 2. 现状盘点：缺什么

| 项 | 现状 |
|---|---|
| 网络权限 | ❌ 无 `INTERNET` |
| HTTP 客户端 | ❌ 无（依赖只有 appcompat / core-ktx / coroutines / material） |
| 下载 | ❌ 无 |
| 安装 | ⚠️ 已有 `FileProvider`（`${applicationId}.fileprovider`），但 `file_paths.xml` 只放了 `cache/logs` |
| 版本号 | ⚠️ `versionCode = 1` / `versionName = "1.0"`，**发版策略需先定**（见 §7.4） |
| 可复用的范式 | ✅ `data/license/LicenseProvider` + `LocalLicenseProvider`（接口 + 本地占位，UI 零改动切换） |

---

## 3. 总体设计

### 3.1 分层

```
data/ota/
  OtaManifest.kt        服务端返回的更新描述（纯数据，界面/状态机只认它）
  OtaProvider.kt        interface：suspend fun latest(current: OtaQuery): Result<OtaManifest?>
  LocalOtaProvider.kt   占位实现：从 files/ota_manifest.json 读，供本地联调（默认注入）
  RemoteOtaProvider.kt  正式实现：HTTPS GET + JSON 解析（服务端就绪后启用）
  OtaStore.kt           持久化：忽略的版本、上次检查时间、etag、已下载文件信息
  OtaManager.kt         状态机 + 编排（单例，StateFlow 暴露状态给 UI）
  OtaDownloader.kt      HttpURLConnection + Range 断点续传 + sha256
  OtaVerifier.kt        三道校验（包名 / 签名 / 版本号）+ sha256
  OtaInstaller.kt       FileProvider → ACTION_VIEW 安装 + 「未知来源」引导
service/
  OtaService.kt         前台服务（dataSync 类型）承载下载，通知栏显示进度
```

**分层原则与引擎一致**：`OtaProvider` 只负责"拿到 manifest"，下载/校验/安装完全不依赖 provider，
所以**整条链路可以先用 `LocalOtaProvider` 跑通**，服务端上线只换一行注入。

### 3.2 状态机

```
IDLE ──check()──> CHECKING ──无更新/304──> IDLE（静默）
                     │ 有更新
                     ▼
                  AVAILABLE ──用户点「立即更新」──> DOWNLOADING
                     │                                 │
                     │                                 ▼
                     │                             VERIFYING ──失败──> FAILED（删包，可重试 1 次）
                     │                                 │ 通过
                     │                                 ▼
                     └────────────────────────────> READY_TO_INSTALL ──> INSTALLING（跳系统安装器）
```

- 状态单一来源：`OtaManager.state: StateFlow<OtaState>`，主界面与通知各自 collect（与 `TaskEngine.progress` 同构）。
- **下载中切后台不中断**：跑在 `OtaService`，进程被系统杀后能从 `.part` 文件续传。

---

## 4. 云端接口契约（直接给服务端）

### 4.1 请求

```
GET {baseUrl}/v1/ota/latest
    ?app=dgg
    &channel=stable          # stable / beta，灰度用
    &versionCode=1
    &abi=arm64-v8a
    &deviceId=<ANDROID_ID+首启UUID>
    &etag=<上次响应的 ETag，可空>
Header: If-None-Match: <etag>
```

- **304 Not Modified** → 客户端认为无更新（省流量，服务端可控）。不实现 etag 也行，返回完整 JSON 即可。
- 建议服务端返回 `Cache-Control: no-store`（这是版本探测，不该被中间层缓存）。

### 4.2 响应（有更新）

```json
{
  "code": 0,
  "data": {
    "versionCode": 12,
    "versionName": "1.2.0",
    "url": "https://cdn.example.com/dgg/1.2.0/dgg-1.2.0-release.apk",
    "size": 15728640,
    "sha256": "9f2c...（64 位小写十六进制）",
    "changelog": "1. 追最新评论修复\n2. 参数可自由设定",
    "publishedAt": 1761600000000,
    "channel": "stable"
  }
}
```

### 4.3 响应（无更新）

```json
{ "code": 0, "data": null }
```

### 4.4 字段语义（必须与客户端一致，否则会被静默忽略）

| 字段 | 必填 | 客户端行为 |
|---|---|---|
| `versionCode` | ✅ | ≤ 当前版本 → **视为无更新**（不做降级安装） |
| `versionName` | ✅ | 仅展示 |
| `url` | ✅ | **必须 https**（仅 127.0.0.1 允许 http，供本地联调）；否则拒绝并记日志 |
| `size` | ⭕ | 仅用于进度显示；**不用于校验** |
| `sha256` | ✅ | 下载后逐字节校验，不匹配即删包重下（最多 2 次） |
| `changelog` | ⭕ | 展示，超长截断 |
| `channel` | ⭕ | 仅日志 |
| `publishedAt` | ⭕ | 仅日志 |

> **本期不做「强制更新」**（用户 2026-10-28 决定）：契约里**不出现** `force` 与
> `minSupportedVersionCode`，客户端永远允许「稍后 / 忽略此版本」。
> 若日后要加，只需在 manifest 增加一个布尔字段并加一个 UI 分支，不影响现有字段。

> **刻意不从服务端取"签名指纹"**：客户端自己把下载包的签名与**当前已安装应用**的签名比对即可。
> 让服务端下发期望指纹，等于把信任建立在同一个地方，没有增加任何安全性。


---

## 5. 安全设计（四道校验 + 下载层 sha256，缺一不可）

因为本应用走侧载分发，客户端就是唯一的信任边界，OTA 一旦被利用等于提权后门。

| # | 校验 | 实现 | 防什么 |
|---|---|---|---|
| 0 | **sha256** | `OtaDownloader` 边下边算 digest，与 manifest 比对（不符删 `.part` 重试） | 传输损坏、CDN 缓存污染 |
| 1 | **能解析成 APK** | `getPackageArchiveInfo` 返回 null 即拒 | 下到 HTML 错误页 / 截断文件 |
| 2 | **包名一致** | `archive.packageName == context.packageName` | 装成别的应用 |
| 3 | **签名一致** | 下载包与**本机已安装应用**的 `apkContentsSigners` 逐字节比对 | 服务端被攻破 / 中间人替换包 / 二次打包 |
| 4 | **版本号递增** | `newVersionCode > 当前 versionCode` | 降级攻击（把人降到有漏洞的旧版） |

第 3 道的关键是**不信任服务端下发的指纹**——只跟本机已安装应用比。让服务端下发期望值
等于把信任建立在同一个地方，没有增加任何安全性。

实现要点（minSdk 24，签名 API 在 28 变过，`OtaVerifier` 里做了双分支）：

```kotlin
val flags = if (SDK_INT >= P) PackageInfoFlags.of(GET_SIGNING_CERTIFICATES.toLong()) else GET_SIGNATURES
val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return Rejected("不是有效的 APK")
if (archive.packageName != context.packageName) return Rejected("包名不符")
val mine = context.packageName.let { pm.getPackageInfo(it, flags) }.signersOf()
if (setOfBytes(mine) != setOfBytes(archive.signersOf())) return Rejected("签名与当前应用不一致（拒绝安装）")
if (archive.versionCodeOf() <= currentVersionCode) return Rejected("版本号未递增")
```

另外：

- 明文流量一律禁止（`usesCleartextTraffic="false"` + `network_security_config.xml`），
  仅对 `127.0.0.1` / `localhost` 开例外，供 `adb reverse` 本地联调。
- 下载只落 `cacheDir/ota/`；`.part` 长度即续传进度，校验不过立刻删。
- **永远不自动安装**：即使服务端标记强更，也必须用户点一次系统安装器确认。

---

## 6. 界面与交互

| 位置 | 内容 |
|---|---|
| 主界面顶部（`tv_log` / `tv_unbind` 旁边） | 新增 `tv_update`：「升级」（有新版本时变品牌红 + 小红点） |
| 点击 | 弹窗：`1.2.0（当前 1.0）`、包大小、更新说明、[立即更新] [稍后] [忽略此版本] |
| 下载中 | 弹窗内进度条 + 通知栏进度；可关弹窗后台下 |
| 下载完 | 弹窗变 [立即安装]，跳系统安装器 |
| 忽略此版本 | 记入 `OtaStore.ignoredVersionCode`，该版本不再提示（更高版本仍提示） |
| 启动 | 静默检查（`Settings.otaAutoCheck` 默认开，6 小时内不重复，失败静默） |

**不做强制更新**：任何情况下都允许「稍后 / 忽略」，也**不会**拦截「开始服务」。
理由（用户决定）：强制更新在侧载场景下风险不对称——万一新包在某个 ROM 上装不上，
用户会被彻底锁死在一个用不了的版本上，连降级都做不到。


「未知来源安装」权限（`REQUEST_INSTALL_PACKAGES`）需要单独引导，复用现有 `PermissionCardView`
的风格，但**不做成常驻权限卡片**——只在用户第一次点安装时弹一次 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`。

---

## 7. 与现有工程约束的冲突点（这是最容易翻车的地方）

### 7.1 任务运行中不能装

安装 APK 会杀进程，正在跑的关注/直播互动会**直接中断且不留终态**。
→ `OtaManager.install()` 前置检查 `TaskEngine.progress.value.state.isActive`：
在跑就提示「请先停止当前任务再升级」，**不自动停止用户的任务**（那是破坏性操作）。

### 7.2 前台服务类型

下载要用前台服务，需要新增 `android:foregroundServiceType="dataSync"` + 权限
`FOREGROUND_SERVICE_DATA_SYNC`（API 34+）。与现有 `specialUse` 是两条独立声明，互不影响。
代价：下载期间通知栏会多一条"正在下载 x%"——这是必要的，否则后台下载会被杀。

### 7.3 国产 ROM 会拦自装

HyperOS / ColorOS 对"侧载应用自安装"有「风险应用」拦截，可能需要用户关闭"纯净模式"或二次确认。
→ 安装失败（`ActivityNotFoundException` / 无响应）时给出**明确文案**，不要静默失败：
「系统拦截了安装：请允许抖关关安装应用，或暂时关闭安全拦截」。

### 7.4 versionCode 策略（必须先定）

现在是 `versionCode = 2 / versionName = 1.1`（授权服务端 + OTA 这一批）。OTA 的整套判断依赖它单调递增：

- 每次发布 +1，**永不复用、永不减小**；
- `versionName` 与 `versionCode` 一一对应（如 1.0.0/1、1.0.1/2 …）；
- 建议改成从 `gradle.properties` 读，避免手改两处漏改：

```kotlin
versionCode = (project.findProperty("dgg.versionCode") as String? ?: "1").toInt()
versionName = project.findProperty("dgg.versionName") as String? ?: "1.0"
```

### 7.5 网络栈依赖

本期建议**不引入 OkHttp**，用 `HttpURLConnection`：只需要 GET + Range + 超时，零新增依赖，
与工程现有"极简依赖"风格一致（当前只有 4 个 implementation）。
代价：重定向/超时/异常要手写，约 150 行，比 OkHttp 多些样板，但可控且不增加 APK 体积与混淆面。

### 7.6 「升级后权限会不会丢」——实测 + 守卫

这是升级功能里最容易被忽略、后果又最严重的一环：本应用**离开无障碍 / 悬浮窗 / 通知就静默残废**，
而这三项恰好都是"系统层面可能被重置"的授权。

**实测结论（OPPO PLB110 / Android 17）**：

| 操作 | 无障碍 | 悬浮窗 | 通知 | 结论 |
|---|---|---|---|---|
| `install -r` 升级 v1 → v2（同签名、versionCode 递增） | 保留 | 保留 | 保留 | ✅ 正常 OTA 升级**不丢权限** |
| `install -r` 覆盖同 versionCode 重装 | 保留 | 保留 | 保留 | ✅ |
| 卸载重装（`firstInstallTime` 变化） | 丢 | 丢 | 丢 | ❌ 全丢 |
| `am force-stop` | **丢**（Secure 表被清空） | 保留 | 保留 | ❌ 无障碍单独丢 |

即：**正常升级路径本身是安全的**，真正的危险来自卸载重装与强制停止 —— 前者是开发期常态，
后者是系统清理/用户手动操作的常见结果。

**因此加了守卫 `data/PermissionGuard.kt`**：

- 记住每个权限**是否曾经授予过**（`ever_*` 只置 true、永不回清）；
- 每次 `onResume` 读一次真实状态，发现「曾有今无」就弹「权限被重置了」，
  逐条列出缺失项 + 该项失效的具体后果，并给「逐项恢复」向导（无障碍 → 悬浮窗 → 通知）；
- 恢复向导**每项只跳一次**：用户拒绝授权不会被无限弹回设置页，剩下的人工点卡片处理；
- 通知项走系统权限弹窗（`POST_NOTIFICATIONS`），推进逻辑挂在 launcher 回调上（不是 `onResume`）。

**验证证据**：`am force-stop` 后重启 → 弹窗只列出「无障碍服务」（另两项确实还在）→
点「逐项恢复」跳到无障碍设置页（`mCurrentFocus=com.android.settings/SubSettings`）→
恢复后回到应用，日志 `权限状态：无障碍=true 悬浮窗=true 通知=true`。

**给运维的一句话**：发版永远用**同签名 + versionCode 递增**的正常升级；
需要卸载重装时（例如换签名），必须提前告知用户"升级后要重新授权"。

---

## 8. 分阶段实施

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **P0** 接口与骨架 | `OtaManifest` / `OtaProvider` / `LocalOtaProvider` / `OtaStore` / `OtaManager` 状态机 | 能"伪造一个 manifest → 进入 AVAILABLE 状态" | ✅ d0aaa87 |
| **P1** 下载与校验 | `OtaDownloader`（Range + sha256）、`OtaVerifier`（4 道校验） | 能把本地/远端 APK 下完并验签 | ✅ d0aaa87 |
| **P2** 安装与 UI | `OtaInstaller`、`file_paths.xml` 加 ota 目录、顶部入口、更新弹窗、未知来源引导 | 端到端可装 | ✅ 710bd3e |
| **P3** 后台与服务 | `OtaService`（dataSync）前台下载 + 通知进度 + 取消、冷启动恢复待安装入口 | 切后台不中断 | ✅ 见 §9.3 |
| **P4** 联调与文档 | `RemoteOtaProvider`、`docs/OTA-接口契约.md`（给云端）、LogStore 埋点 | 可交付 | ✅ 代码 + 契约见 `docs/OTA-接口契约.md` |

**强制更新已确认不做**（用户拍板），所以「落"拦截开始服务"」从 P3 里去掉：
更新提示永远可以被「稍后 / 忽略此版本」跳过。

预估 3 天（P0 半天 / P1 一天 / P2 半天 / P3 半天 / P4 半天）。

---

## 9. 不依赖云端的自测方案

这是本期能否"本地先做好接口"的关键——**整条链路必须能在没有服务端时验证**：

1. **manifest 注入**：`LocalOtaProvider` 读 `files/ota_manifest.json`，用 `adb push` 或调试通道写入。
   服务端就绪前，改这行注入即可，UI 与状态机零改动。
2. **DebugChannel 命令**（本期实测可用）：
   - `OTAJSON <json>`：覆盖 `files/ota_manifest.json`，构造"有更新 / 非法 url"等场景；
   - `OTACHECK`：走完整 `OtaManager.check()`，等价于点「升级」；
   - `OTADL <url> <sha256>`：**只测下载 + 校验**（不落 `Ready` 状态），验证续传与 sha256；
   - `OTAVERIFY <绝对路径>`：直接喂一个本地 APK 给 `OtaVerifier`，负向用例全靠它；
   - `OTASTART <versionCode> <versionName> <url> <sha256>`：走**生产链路** `OtaService`
     前台下载 + 通知进度。P3 靠它验证，不需要授权，也不用点界面（「升级」入口要授权后才出现）。
3. **本地就有一个"真"下载服务器**：`python -m http.server` **不支持 Range**（一律 200），
   只能验证降级分支。要验 206 必须用支持 Range 的服务端（自写的 `.tmp/ota-test/range_server.py`，
   行为对齐 CDN：`Range: bytes=N-` → 206 + `Content-Range`，起点越界 → 416）。
4. **正向用例**：同签名（debug keystore）、`versionCode=2` 的包 → 下载后 sha256 通过、四道校验全过。
5. **负向用例**：见 9.2，六个必须逐个过。
6. **断点续传**：造一个 1MB 的 `.part` 后重新下载，应从 1048576 续；`.part` 比源文件还大时应回 416 后自愈。

### 9.1 真机自测结果（2026-10-28，OPPO PLB110 / Android 17 / 抖音 40.8.0 无关联）

安装态 `versionCode=1`，测试包 `versionCode=2 / versionName=1.0.1`（同一 debug 签名），
sha256 `21c494b2…10c1c156`，`adb reverse` + 本地 HTTP 提供。

| # | 用例 | 期望 | 实测日志 |
|---|---|---|---|
| P1 | `OTACHECK`（合规 manifest，v2） | 有新版本 | `发现新版本 1.0.1(2)，当前 1` |
| P2 | `OTADL` 正常下载 | 下完 + sha256 通过 + 验签通过 | `下载完成并校验通过：dl-test.apk（5994021 字节）` → `校验通过：1.0.1(2)，签名 ca2a6642563a7a56…` |
| N1 | sha256 改全 0 | 重试 2 次后失败、删 `.part` | `sha256 不匹配，删除重下：期望 0000…` → `第 2/2 次下载失败：sha256 校验不通过` |
| N2 | `http://192.168.1.5:8099/…`（非回环） | 发起请求前就拒 | `OTADL 失败：下载地址不被允许` |
| N3 | `versionCode=1` 的包（= 当前） | 拒绝降级 | `拒绝：版本号未递增（下载包 1 / 当前 1）` |
| N4 | 同一 APK 用另一张证书重签 | 拒绝异签 | `拒绝：签名与当前应用不一致（拒绝安装）`，日志给出双方指纹 `ca2a6642…` / `af8b2d20…` |
| N5 | `Settings.apk`（包名不同） | 拒绝异包名 | `拒绝：包名不符：com.android.settings（应为 com.example.dgg）` |
| N6 | 文本文件改名 `.apk` | 拒绝非法包 | `拒绝：不是有效的 APK（解析失败，可能下载不完整）` |
| N7 | 1MB `.part` + Range 服务端 | 从 1048576 续传 | `断点续传：从 1048576 字节继续` → `下载完成并校验通过` |
| N8 | manifest 用 `http://192.168.1.5` | 判为不可用 → 无更新 | `manifest 不可用：url 不是 https 或不可解析` → `OTACHECK 结果：无更新` |
| N9 | `.part` 比源文件大（7MB） | 416 后自愈 | `第 1/2 次下载失败：HTTP 416` → 第二次从头下完并校验通过 |

**结论：P0+P1 在无服务端条件下全绿。** 值得记一笔的两个"不是 bug 的行为"：
`http.server` 不支持 Range 会让续传降级成重下（日志有 W 提示，不影响正确性）；
`sha256` 不符时必须失败而不是"重下直到对上"——否则等于给了中间人无限次尝试。

### 9.2 自测过程的四个操作坑（都踩过，写下来省一次）

1. **`echo` 写 `files/debug_cmd` 会把 JSON 的引号、花括号、逗号吃掉**，本机实测落到磁盘变成
   `versionCode:2 versionName:1.0.1 url:http://…`（引号全丢，`OtaManifest.parse` 必然失败）。
   ⇒ 带 JSON 的命令一律 **`adb push` 到 `/data/local/tmp` → `run-as … cp` 进 `files/debug_cmd`**。
2. **`adb reverse` 挂在 adb 服务进程上**，本机每条 Bash 调用都会重启守护进程 ⇒ 转发映射立即丢失
   （表现为 `Failed to connect to /127.0.0.1:8099`）。⇒ **`reverse` 与触发命令、读日志必须在同一次调用里**。
3. **息屏 = 进程被冻结**，调试通道的 800ms 轮询整个停摆（命令文件躺在 `files/debug_cmd` 不动）。
   先 `input keyevent KEYCODE_WAKEUP` + `am start` 拉回前台再发命令；
   必要的话 `settings put system screen_off_timeout 2147483647` 关掉自动息屏。
4. **`adb push` 的目标是设备路径时**必须 `MSYS_NO_PATHCONV=1`，否则 `/data/local/tmp/x` 会被 MSYS
   翻译成 `D:/software/.../data/local/tmp/x` 而失败（`remote secure_mkdirs() failed`）；
   但**源路径要留相对路径**（如 `.tmp/ota-test/x`），否则同样被当成不存在的绝对路径。

### 9.3 P3 真机验证（2026-10-28，OPPO PLB110 / Android 17）

前置：`OTASTART 2 1.0.1 <url> <sha256>`，服务端限速 400 KB/s（`.tmp/ota-test/range_server.py?rate=`）
把 6 MB 的包拉成 ~15 秒，好在下载途中切后台。

| 观察点 | 实测 |
|---|---|
| 服务起来了 | `OTASTART：已请求 OtaService 下载 1.0.1(2)` → `OTA服务: 开始下载 1.0.1(2)` |
| **FGS 类型正确** | `ServiceRecord{...OtaService}` 里 `isForeground=true foregroundId=1002 **types=0x00000001**`（= `DATA_SYNC`） |
| 通知挂着 | `foregroundNoti=Notification(channel=dgg_ota ... flags=ONGOING_EVENT|ONLY_ALERT_ONCE|FOREGROUND_SERVICE actions=1)` |
| **切后台不中断** | 22:15:26 开始 → 按 HOME → 22:15:41 `下载完成并校验通过：dgg-2.apk（5994021 字节）` |
| 服务自行退出 | 完成后 `dumpsys activity services` 里 OtaService 记录数 = 0 |
| 冷启动恢复入口 | `恢复待安装状态：1.0.1(2)`，标题栏出现红色「安装 1.0.1」（截图确认） |
| 记录自清理 | 包被删掉后重启：`上次的安装包已不可用（文件缺失或记录损坏），清理` |

**体感差别**：改造前下载绑在 `MainActivity.uiScope` 上，切后台被冻结/重建即取消；
现在下载归服务、界面只订阅 `OtaManager.state`，通知栏有进度和「取消下载」。

一个副作用要知道：Android 的 `cache/ota` 是「下载完 ≠ 装上」，所以**冷启动必须恢复 Ready 状态**，
否则用户会在缓存里留个 6 MB 的包却找不到「安装」按钮。这就是新增 `OtaStore.readyManifestJson`
与 `OtaManager.restoreReady()` 的原因（只存路径 + versionCode 恢复不出完整界面）。


---

## 10. 已确认的决策（2026-10-28）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 网络栈 | **`HttpURLConnection`**（零依赖，与现有 4 个 implementation 的极简风格一致） |
| 2 | 下载承载 | **前台服务 `dataSync`**（P3 实现） |
| 3 | 强制更新 | **不做**。永远允许「稍后 / 忽略」，不拦截「开始服务」 |
| 4 | 入口 | 顶部「升级」按钮 + 启动静默检查 |
| 5 | versionCode | 发版时手改 `build.gradle.kts`，**每次 +1、永不复用**；后续再考虑抽到 `gradle.properties` |
| 6 | 本期范围 | **P0 + P1**（接口 + 下载校验），UI / 安装 / 后台服务留到下一阶段 |

### 本期（P0+P1）交付清单

- `data/ota/OtaManifest.kt` — 服务端 JSON 契约 + 校验
- `data/ota/OtaProvider.kt` — 接口 + `OtaQuery`
- `data/ota/LocalOtaProvider.kt` — 占位实现（读 `files/ota_manifest.json`）
- `data/ota/OtaStore.kt` — 检查时间 / etag / 忽略版本
- `data/ota/OtaManager.kt` — 状态机 `StateFlow<OtaState>` + check/downloadAndVerify
- `data/ota/OtaDownloader.kt` — Range 断点续传 + 流式 sha256
- `data/ota/OtaVerifier.kt` — 四道校验（可解析 / 包名 / 签名 / 版本号）
- 清单：加 `INTERNET`、`ACCESS_NETWORK_STATE`、`networkSecurityConfig`（禁明文，仅回环例外）
- `res/xml/network_security_config.xml`
- 调试通道：`OTAJSON <json>` / `OTACHECK` / `OTADL <url> <sha256>` / `OTAVERIFY <path>`

**不含**：安装（`OtaInstaller`）、UI、前台下载服务、静默检查开关 —— 属 P2/P3。

