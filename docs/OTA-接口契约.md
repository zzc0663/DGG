# OTA 接口契约（给云端）

面向实现服务端的人。客户端代码是唯一权威：本文每条约束都能在
`app/src/main/java/com/example/dgg/data/ota/` 下找到对应实现，标注在 §8 的对照表里。

> 版本：对应客户端 `versionCode=2 / versionName=1.1`。
> 客户端**不做强制更新**（2026-10-28 决定），所以契约里没有 `force` / `minSupportedVersionCode`
> 这类字段 —— 不要下发，下发也不会生效。

---

## 1. 两条接口，各自的最小要求

| # | 接口 | 谁调用 | 关键要求 |
|---|---|---|---|
| 1 | `GET /v1/ota/latest` | 客户端查有无新版本 | HTTPS、UTF-8 JSON、快（客户端 8s 连接 / 10s 读超时） |
| 2 | 安装包直链（任意 https URL） | 客户端下载 APK | **必须支持 Range**、Content-Length 准确、内容不可变 |

安装包不放 `/v1` 下也可以，`data.url` 给什么就下什么（只要是 https）。但**必须支持
`Range: bytes=N-`**，否则断点续传会退化成每次从头下（功能不坏，但用户流量白花）。

---

## 2. 传输约定

| 项 | 约定 |
|---|---|
| 协议 | **HTTPS**。客户端 `network_security_config` 禁明文，仅放行 `127.0.0.1` / `localhost`（供 `adb reverse` 联调） |
| 字符集 | UTF-8（无 BOM） |
| 请求头 | 客户端只发 `Accept: application/json`，**不发 gzip / 不发 Authorization** |
| 响应体 | **不要 gzip 压缩 JSON 之外的二进制**；APK 必须原样字节传输（压缩会改变 `Content-Length` 语义，也让 `Range` 变复杂） |
| 时间戳 | 毫秒（`publishedAt`），与时区无关的 epoch |
| 大小 | 字节（`size`） |
| 方法 | 只读 GET。客户端不会 POST，也不需要 CSRF/cookie |

---

## 3. 接口 1：查询最新版本

```
GET https://<host>/v1/ota/latest?app=dgg&channel=stable&versionCode=2&abi=arm64-v8a&deviceId=<sha256 64 位十六进制>
```

### 3.1 请求参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `app` | 是 | 固定 `dgg`（将来多产品线时区分） |
| `channel` | 是 | 当前客户端固定 `stable`。灰度放服务端按 `channel + deviceId` 自行决定 |
| `versionCode` | 是 | **客户端当前已安装的 versionCode**，服务端据此判断"是否有更新" |
| `abi` | 是 | `Build.SUPPORTED_ABIS.firstOrNull()`，如 `arm64-v8a`；可能是空串 |
| `deviceId` | 是 | 64 位小写十六进制，`sha256("<ANDROID_ID>:<installUuid>:<packageName>")`（见 `DeviceIdentity`）。**不是明文 ANDROID_ID**，客户端不采集 IMEI / MAC / 序列号。仅用于灰度与去重统计；清数据或重装会变 |

客户端不发 ETag（`OtaQuery.etag` 恒为 null），所以**不要依赖 `If-None-Match`**。
如果服务端自己实现了 304，客户端也能正确处理（见 3.4）。

### 3.2 响应：有新版本

HTTP 200，`application/json`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "versionCode": 3,
    "versionName": "1.2",
    "url": "https://cdn.example.com/dgg/dgg-3.apk",
    "sha256": "9f2c4e5b1a7d8c3f0e6b2a4d7c1f8e9b3a5d2c6f1e4b7a0d3c9f2b5e8a1d4c70",
    "size": 7013396,
    "changelog": "1. 支持评论追最新\n2. 修复升级后权限丢失提示",
    "channel": "stable",
    "publishedAt": 1793200000000
  }
}
```

### 3.3 字段语义（**只有这份白名单里的键会被读取**）

| 字段 | 类型 | 客户端如何使用 | 可变性 |
|---|---|---|---|
| `versionCode` | int | 必须 **> 请求里的 `versionCode`**，否则客户端当"无更新" | 单调递增，永不复用 |
| `versionName` | string | 纯展示（弹窗标题、通知），非空 | 任意 |
| `url` | string | 下载地址，**必须 https** | 每个版本不同，建议带版本号 |
| `sha256` | string | 64 位十六进制（大小写都行，客户端转小写比较），逐字节校验 | 发布后不可变 |
| `size` | long | **仅用于进度显示**，不参与校验 | 应为 APK 字节数；可省略（进度变不确定态） |
| `changelog` | string | 弹窗正文，客户端截断 800 字 | 可含 `\n` |
| `channel` | string | 回显，不参与判断 | `stable` / `beta` |
| `publishedAt` | long | 毫秒时间戳，暂不展示 | — |

**不要下发**：`force`、`minSupportedVersionCode`、`signature`、`md5`、`packageName`。
客户端不读（白名单外一律忽略），下发了会让人以为生效了。

### 3.4 响应：无更新

三种都合法，客户端行为一致（静默，不打扰用户）：

| 场景 | 响应 |
|---|---|
| 已是最新 | HTTP 200 + `{"code":0,"data":null}` |
| 该渠道无发布 | 同上 |
| 客户端带的 `versionCode` 已 ≥ 最新 | 同上（客户端还有一道本地兜底，见 §8） |
| 缓存未变（可选） | HTTP 304，空 body —— 客户端仅在做 ETag 时才发 `If-None-Match`，当前不会触发 |

### 3.5 错误与失败

| 情况 | 建议响应 | 客户端表现 |
|---|---|---|
| 服务端异常 / 维护 | 5xx | 记一条 `远程检查失败`，本次静默跳过，**不弹任何窗** |
| 参数错 | 4xx | 同上（不会重试、不会提示用户） |
| 网络不可达 | — | 同上 |

**重要**：客户端把「非 2xx」一律当"查不到"，不会当"有新版本"。所以**不要用 HTTP 200 +
`code != 0` 表达错误**再指望客户端识别 —— 客户端只读 `data`，`code` 仅作日志。

### 3.6 `data` 结构容错

客户端同时接受两种形状（`OtaManifest.parse`）：

```json
{"code":0,"data":{ ... }}     // 推荐
{ ... }                        // 裸对象也认
```

以下任何一项不满足，**整条 manifest 会被丢弃**（记 `manifest 不可用：…`，当作无更新）：

- `versionCode <= 0`
- `versionName` 为空
- `url` 不是 `https://`（唯一例外：`http://127.0.0.1:*` / `http://localhost:*` 供联调）
- `sha256` 不是 64 位十六进制

---

## 4. 接口 2：安装包下载

```
GET https://cdn.example.com/dgg/dgg-3.apk
```

### 4.1 必须满足

| 要求 | 原因 |
|---|---|
| **支持 `Range: bytes=N-`**，命中时回 `206` + `Content-Range: bytes N-(size-1)/size` | 断点续传。不支持也能用（客户端会回退成 200 整包重下），但用户会重复下载 |
| 起点越界回 `416` + `Content-Range: bytes */size` | 客户端据此判定 `.part` 已损坏并重下 |
| `Content-Length` 准确 | 只用于进度条；**校验靠 sha256**，所以这里写错不会装坏包，但进度会错 |
| 同一 URL 内容不可变 | 客户端会把已下载并通过 sha256 的文件留在 `cache/ota/` 里复用；内容变了就会校验失败重下 |
| CDN 缓存要带版本号路径 | 用 `/dgg/dgg-<versionCode>.apk`，避免"发新版但 CDN 还在发旧包" |

### 4.2 客户端下载行为

- 落盘：`cacheDir/ota/dgg-<versionCode>.apk`，下载中写 `.part`（**`.part` 的长度就是续传进度**）
- 进度回调约 200ms 一次，通知栏显示百分比
- sha256 不匹配 → 删 `.part` → 重试；`MAX_ATTEMPTS=2` 次后失败（不会无限烧流量）
- 连接超时 15s / 读超时 30s / 16KB 缓冲
- 下载在**前台服务**（`dataSync`）里跑，切后台/息屏不中断；用户可以点通知里的「取消下载」
- 下载完成**不会自动安装**，必须用户点「安装」→ 系统安装器

---

## 5. 客户端会做的四道校验（服务端必须满足）

这一步是安全底线：本应用走侧载分发，客户端是唯一信任边界。任何一道不过直接拒绝安装。

| # | 校验 | 服务端因此必须保证 |
|---|---|---|
| 0 | sha256 逐字节比对 | `sha256` 与实际文件**完全一致**（发布后用 `sha256sum` 复算一次） |
| 1 | 能解析成 APK | `url` 指向真的 APK，不是错误页/HTML |
| 2 | 包名一致 | APK 的 `applicationId` 仍是 `com.example.dgg` |
| 3 | 签名与**本机已安装应用**逐字节一致 | 用**同一把签名密钥**打包。换签名 = 用户必须卸载重装 = 权限与本地数据全丢 |
| 4 | `versionCode` 严格递增 | 永不回退、永不重复使用同一个号 |

注意第 3 道：**客户端不信任服务端下发的任何签名指纹**，只跟本机已安装应用比。
所以契约里没有 `signature` 字段，也不需要它。

---

## 6. 发布流程检查清单

服务端每次发版照着走：

1. 打包 → `apksigner verify` 通过 → 记下 `versionCode` / `versionName`
2. `sha256sum app-release.apk` → 填进 `sha256`；`stat -c %s` → 填 `size`
3. 上传到**带版本号的路径**（`/dgg/dgg-<versionCode>.apk`），确认公开可下载、支持 Range：
   ```bash
   curl -I -H "Range: bytes=0-1" https://cdn.example.com/dgg/dgg-3.apk
   # 期望：HTTP/1.1 206 Partial Content + Content-Range: bytes 0-1/7013396
   ```
4. `versionCode` 必须大于线上已发布的所有版本（含 beta 通道）
5. 更新 `/v1/ota/latest` 的返回值（**灰度时按 `deviceId` 取模**，不要按 `channel` 一刀切）
6. 回滚 = 把接口改回上一个 `versionCode` 的 manifest；**不要**指望客户端降级（会被拒）
7. 发布后自测：`curl` 拉一次接口核对 `url/sha256/size` 与文件一致

---

## 7. 客户端侧策略（服务端了解即可）

| 行为 | 值 |
|---|---|
| 自动检查间隔 | **6 小时**（`OtaManager.MIN_CHECK_INTERVAL_MS`），失败不额外重试 |
| 手动检查 | 点顶部「升级」，不受间隔限制、不看"忽略版本" |
| 忽略此版本 | 记在本地 `SharedPreferences`（`ignoredVersionCode`），**不上报服务端**；更高版本仍会提示 |
| 自动检查时机 | 仅在**授权有效**后（`license.authorized`）触发 |
| 强制更新 | **不做**。永远允许「稍后 / 忽略此版本」 |
| 安装时机 | 任务运行中拒绝安装（会杀进程）；用户点「安装」才调系统安装器 |
| 冷启动恢复 | `cache/ota` 里的包若已校验通过，重启后标题栏仍显示「安装 vX」 |

因此服务端**不需要**考虑"客户端卡在旧版本不走"以外的策略：想推动升级就提高 `versionCode`
并写好 `changelog`。

---

## 8. 字段 → 客户端代码对照

| 字段/行为 | 实现位置 |
|---|---|
| 请求参数拼装 | `data/ota/RemoteOtaProvider.kt`（`latest()`） |
| 响应解析白名单 | `data/ota/OtaManifest.kt`（`parse()` / `toJson()`） |
| url 白名单（https / 回环例外） | `data/ota/OtaManifest.kt`（`OtaUrls.isAllowed`） |
| 本地兜底"不高于当前即无更新" | `data/ota/RemoteOtaProvider.kt`、`LocalOtaProvider.kt` |
| 6 小时间隔 / 忽略版本 / 状态机 | `data/ota/OtaManager.kt` |
| Range、sha256、重试 | `data/ota/OtaDownloader.kt` |
| 四道校验 | `data/ota/OtaVerifier.kt` |
| 前台服务下载 + 通知 | `service/OtaService.kt` |
| 安装（系统安装器 + 未知来源引导） | `data/ota/OtaInstaller.kt` |

---

## 9. 四种"看起来能用其实会坏"的做法

1. **302 跳到 `http://`**：客户端只校验初始 URL 的 scheme，跳转后明文会被 `network_security_config` 拦掉，
   表现为 `下载失败：Cleartext HTTP traffic not permitted`。CDN 请全程 https。
2. **APK 走 gzip / chunked 且不带 `Content-Length`**：sha256 仍能通过（校验的是解压后文件），
   但 `Range` 与进度都会错乱。二进制直链请关闭压缩。
3. **`size` 写成 0 又不带 `Content-Length`**：通知栏进度变成不确定态（转圈）。
4. **复用同一个 URL 覆盖内容**：老客户端会把上次校验通过的文件直接复用（跳过下载），
   永远不会拿到新包。**每个 `versionCode` 用独立路径**。

---

## 10. 本地联调（不需要公网）

客户端 debug 包的 `API_BASE_URL` = `http://127.0.0.1:3000/`（`app/build.gradle.kts`），
配合 `adb reverse` 就能把整条链路跑通：

```bash
# 1. 起一个 mock 服务（示例：返回上面 3.2 的 JSON，静态托管 apk）
#    注意 mock 的 apk 必须支持 Range，否则续传分支测不到
python range_server.py 3000        # 项目内 .tmp/ota-test/range_server.py 支持 ?rate= 限速

# 2. 端口转发到手机
adb reverse tcp:3000 tcp:3000

# 3. 触发（调试通道，仅 debug 包；见 docs/OTA-升级方案.md §9）
adb shell "run-as com.example.dgg sh -c 'echo OTACHECK > files/debug_cmd'"   # 查更新
```

`/v1/ota/latest` 的 mock 响应最小可用体：

```json
{"code":0,"data":{"versionCode":3,"versionName":"1.2","url":"http://127.0.0.1:3000/dgg-3.apk","sha256":"<64位hex>","size":7013396}}
```

回环地址是客户端唯一允许的 `http://` 例外（见 `OtaUrls.isAllowed`），其它一律必须 https。

---

## 11. 与授权接口的关系

OTA 与授权是两条独立链路，互不阻塞：

- 授权：`/license/activate`、`/license/heartbeat`、`/license/unbind`（见 `RemoteLicenseProvider`）
- 更新：`/v1/ota/latest`（本文）

唯一交集：**客户端只在授权有效后自动检查更新**（手动点「升级」不受限）。
所以服务端不必给 `/v1/ota/latest` 加鉴权 —— 它不泄露任何用户数据，
`deviceId` 也只是一个 SHA-256 摘要（ANDROID_ID + 首启 UUID + 包名），不可反推。
