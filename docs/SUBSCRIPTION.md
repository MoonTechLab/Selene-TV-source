# 订阅源格式规范（Subscription Format Spec）

本文件定义 LunaTV / Selene 的订阅源数据格式，供**订阅源作者**参考构建。订阅是一个可远程访问的 URL，返回 **Base58 编码后的 JSON**，其中聚合了若干「搜索源（点播）」与「直播源」。

---

## 1. 总览

一份订阅就是一个 HTTP(S) 端点，客户端对它 `GET` 后会：

1. 取得响应正文（一段 Base58 字符串）；
2. Base58 解码为 UTF-8 文本；
3. 按本规范解析 JSON，得到搜索源列表 + 直播源列表。

订阅本身**只承载源清单**，不包含任何用户数据（收藏 / 播放记录 / 搜索历史等均由客户端本地保存，与订阅无关）。作为作者，你要做的就是：按第 4 节组织 JSON → 按第 3 节 Base58 编码 → 让 URL 以 `200` 返回这段字符串。

---

## 2. 传输层要求

| 项目 | 要求 |
|---|---|
| 方法 | 响应 `GET` |
| 协议 | 仅 `http` / `https` |
| 重定向 | **不会被跟随**，请直接提供最终可访问的地址 |
| 状态码 | 必须返回 `200` |
| 响应时间 | 连接 / 读取需在 10s 内完成 |
| 大小上限 | 正文 **≤ 1 MB** |
| TLS | 允许自签证书（客户端不校验证书链与 hostname），但仍建议使用有效证书 |
| 响应正文 | 一段 Base58 字符串（首尾空白会被忽略） |
| `Content-Type` | 不限，任意值均可 |

---

## 3. 编码：Base58（无 checksum）

响应正文是对「UTF-8 JSON 字节」做的 **标准 Base58 编码**，**不带 checksum**。

- 字符集：`123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`（去掉了易混淆的 `0 O I l`）。
- 前导 `1` 表示前导零字节。
- 多数语言可用现成库：JS 的 `bs58`、Python 的 `base58`、Go 的 `btcutil/base58` 等（取其**不含 checksum** 的基础 `base58` 接口，而非 `base58check`）。

> Base58 仅用于编码、便于与既有生成器互通，**并非加密**——它不提供任何保密性，不要把敏感信息放进订阅。

**生成订阅（伪代码）：**

```
json_text  = JSON.stringify(payload)          # 见第 4 节
utf8_bytes = utf8_encode(json_text)
body       = base58_encode(utf8_bytes)         # 无 checksum
serve HTTP 200 with body
```

JS 示例：

```js
import bs58 from 'bs58'
const payload = { api_site: { /* ... */ }, lives: { /* ... */ } }
const body = bs58.encode(Buffer.from(JSON.stringify(payload), 'utf8'))
// 把 body 作为该 URL 的响应正文，状态码 200
```

---

## 4. JSON 结构（Base58 解码后）

顶层是一个对象，包含两个可选键：`api_site`（搜索 / 点播源）与 `lives`（直播源）。**两者均为「map（对象）」**——键是源的唯一标识，值是源定义对象。未识别的键会被忽略。

```jsonc
{
  "api_site": {
    "<source_key>": { /* 搜索源，见 4.1 */ },
    "...": { }
  },
  "lives": {
    "<live_key>": { /* 直播源，见 4.2 */ },
    "...": { }
  }
}
```

> 顶层用 **map 而非数组**：当条目自身缺省 `key` 字段时，map 的 key 会充当兜底标识。

### 4.1 `api_site` 条目 —— 搜索 / 点播源

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `key` | string | 否 | 取所在 map 的 key | 源唯一标识。缺省时回退为外层 map 的 entry key |
| `name` | string | **是** | `""` | 展示名 |
| `api` | string | **是** | `""` | 苹果 CMS（maccms）风格 API 基址，详见第 5 节契约 |
| `detail` | string | 否 | `null` | 详情页站点根（HTML 抓取兜底，见 5.3）。CMS 标准 JSON 详情可用时无需提供 |
| `from` | string | 否 | `null` | 来源标注 / 出处，仅元信息 |

- `name` / `api` 缺省会得到空串；**空 `api` 的源不可用**，请务必提供。
- 单个条目若字段无法解析，会被**静默跳过**，不影响其余源。

### 4.2 `lives` 条目 —— 直播源

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `key` | string | 否 | 取所在 map 的 key | 源唯一标识。缺省时回退为外层 map 的 entry key |
| `name` | string | **是** | `""` | 展示名 |
| `url` | string | **是** | `""` | 直播频道清单地址（通常是 m3u 列表，亦可为单条流地址） |
| `ua` | string | 否 | `""` | 自定义 User-Agent（部分源需要） |
| `epg` | string | 否 | `""` | 节目单（EPG）XML 地址 |
| `from` | string | 否 | `""` | 来源标注 / 出处 |

---

## 5. `api` 字段的 CMS API 契约

`api_site[*].api` 必须指向一个**苹果 CMS（maccms）风格**的 JSON 接口基址（不含 query）。客户端会直接拼接以下 query 调用，源站需按此响应：

### 5.1 搜索（列表）
```
GET {api}?ac=videolist&wd={关键词}
GET {api}?ac=videolist&wd={关键词}&pg={页码}    # 翻页
```
返回标准 maccms `videolist` JSON（含 `list[]`，每项有 `vod_id` / `vod_name` / `vod_pic` / `vod_play_url` 等）。

### 5.2 详情（首选）
```
GET {api}?ac=videolist&ids={vod_id}
```
绝大多数 CMS 源此接口可用，用于取单片详情与播放地址。

### 5.3 详情兜底（HTML）
当 5.2 不可用且条目提供了 `detail` 时，客户端会回退抓取详情 HTML：
```
GET {detail}/index.php/vod/detail/id/{vod_id}.html
```
因此 `detail` 应填**站点根**（不含 `/index.php/...`），例如 `https://example.com`。

> 简言之：`api` 应支持 `ac=videolist` 的 `wd=` 搜索与 `ids=` 详情；`detail` 是可选的 HTML 兜底。

---

## 6. 订阅必须满足的条件

| 条件 | 不满足的后果 |
|---|---|
| URL 合法且为 http(s) | 拒绝加载 |
| 响应状态码为 `200` | 拒绝加载 |
| 正文 ≤ 1 MB | 拒绝加载 |
| 正文为合法 Base58 | 拒绝加载（解码失败） |
| 解码后为合法 JSON | 拒绝加载（解析失败） |
| `api_site` + `lives` 解析后**总条目数 ≥ 1** | 拒绝加载（无可用源） |

注意：客户端只校验「至少有一个源」，**不会**校验各源 URL 是否真的能连通——源是否可用由你自行保证。

---

## 7. 完整示例（Base58 解码后的明文 JSON）

```json
{
  "api_site": {
    "source_a": {
      "name": "示例点播源 A",
      "api": "https://api-a.example.com/api.php/provide/vod",
      "detail": "https://api-a.example.com"
    },
    "source_b": {
      "key": "source_b",
      "name": "示例点播源 B",
      "api": "https://api-b.example.com/api.php/provide/vod",
      "detail": "https://www.example-b.com",
      "from": "demo"
    }
  },
  "lives": {
    "iptv_main": {
      "name": "默认直播",
      "url": "https://live.example.com/playlist.m3u",
      "epg": "https://epg.example.com/guide.xml"
    },
    "ua_required": {
      "name": "需要UA的源",
      "url": "https://live.example.com/special.m3u",
      "ua": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
    }
  }
}
```

将上面这段 JSON 用 Base58（无 checksum）编码后，由订阅 URL 以 `HTTP 200` 返回即可。

---

## 8. 最小可用订阅

只放搜索源、不放直播也合法（反之亦然），只要总数 ≥ 1：

```json
{
  "api_site": {
    "demo": {
      "name": "示例源",
      "api": "https://example.com/api.php/provide/vod"
    }
  }
}
```

---

## 9. 给订阅作者的建议

- **`key` 保持稳定**：它用于客户端去重 / 标识；改 key 等于换了一个新源。
- **`name` 简短可读**：会直接显示在界面上。
- **优先保证 `api` 的 `ac=videolist` 搜索与 `ids=` 详情可用**，能省掉 `detail` HTML 兜底。
- **不要塞敏感信息**：Base58 不是加密，订阅正文可被任何拿到 URL 的人解码。
- **控制体积**：编码前后均建议远小于 1 MB 上限。
- **直播 `url`** 建议为标准 m3u 列表。
</content>
</invoke>
