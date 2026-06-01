# 弹幕源端点参考（misaka .so 逆向提取）

> **用途**：后续在 Selene-TV 客户端实现各弹幕源时的端点速查与实现指引。
> **来源**：从 `l429609201/Misaka-Scraper-Resources` 仓库的编译产物（Cython `.so`/`.pyd`）用 `strings` 提取。
> 这些 `.so` 源码不可读，但 Cython 把 **URL / 参数名 / 函数名 / protobuf 类型**当明文常量塞进二进制，`strings` 即可抽取。
> **校验状态**：`bilibili` 已在本项目实测打通；其余源端点为 strings 提取，**参数细节（尤其签名、分段边界）需抓包或进一步反向确认后再落地**——文档内已标注。
> 版本对应 misaka 资源仓库 `linux-x86/versions.json`（提取于 2026-06-01）。

> **实现状态**（`app/src/main/assets/danmaku/*.js`，截至 2026-06-02，共 15 源全部落地脚本）：
> - ✅ **已实现并实测**：bilibili
> - ✅ **已实现（逆向，待真机首跑核对）**：tencent / iqiyi / youku / mgtv / sohu / le / xigua / renren
> - ✅ **已实现·hanjutv**：curl 真打验证（出口东京），ES5 移植；HXQ 明文 + TV 加密两路合并
> - 🟡 **best-effort（端点已逆，未抓包）**：ezdmw（HTML 正则 + getData XML；sign/nk 抽取为推断，失败安全返空）
> - ⛔ **骨架占位（阻塞，契约函数返 []）**：mddcloud（jsonSign 私钥签名未逆）/ migu（WASM 网关签名）/ gamer（Cloudflare 盾需 FlareSolverr）/ dandanplay（需 AppId 签名，华语覆盖近零）
> - 注：脚本均已就位但**尚未接入 `DanmakuService` 的源选择/聚合**（仍硬编码 bilibili），待 Phase 2 远程下发 + 多源聚合后启用。

## 0. 统一约定

所有源最终归一到本项目的契约（见 `service/danmaku/DanmakuSource.kt`）：

```
search(keyword)       → [DanmakuMedia]      # 候选媒体
getEpisodes(mediaId)  → [DanmakuEpisode]    # 分集，含可取弹幕的 id
getComments(episodeId)→ [DanmakuComment]    # 统一弹幕 {timeMs, mode(1滚/4底/5顶), color(RGB), content}
```

弹幕拉取的通用形态有三类：
- **整片一次拉**（旧 B站 list.so、ezdmw）
- **按时间分段**（多数：腾讯/爱奇艺/优酷/芒果/搜狐/乐视/咪咕/mddcloud——按 6min 或 1min 段循环到空）
- **按集一次拉**（人人/巴哈/韩剧tv）

跨源通用基础设施（部分源必需）：
- **代理**：mgtv 等海外封锁（实测 JP 直连 403），misaka 用 Clash(`127.0.0.1:7890`)/biliroaming。
- **FlareSolverr**（`localhost:8191`）：gamer(巴哈) 过 Cloudflare 盾用。
- **protobuf 解码**：bilibili/iqiyi/xigua——本项目已有手写最小解码器 `BiliDmProto.kt` 可复用扩展。
- **加解密**：renren(AES-ECB 固定 key)、hanjutv(AES-CBC 动态派生)、youku(mtop 签名)、migu(WASM 网关签名)。

---

## 1. bilibili 哔哩哔哩 ✅ 已实现并实测（v1.1.7）

| 项 | 值 |
|---|---|
| 搜索 | `GET /x/web-interface/wbi/search/type?`（wbi 签名）或 `/x/web-interface/search/all/v2`；wbi key 取自 `/x/web-interface/nav` |
| 分集 | PGC：`/pgc/view/web/season?season_id=` → `episodes[].cid`；UGC：`/x/web-interface/view?bvid=` 或 `/x/player/pagelist?bvid=` → cid |
| **弹幕** | `GET https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid={cid}&segment_index={N}` |
| 分段 | `segment_index` 从 1 递增，每段 6min，空段终止 |
| 格式 | **protobuf** `DmSegMobileReply{ repeated DanmakuElem=1 }`；elem：`progress=2(ms) / mode=3 / color=5 / content=7` |
| 兜底 | `/x/v1/dm/list.so?oid={cid}`（deflate XML，`Inflater(nowrap)`） |
| 鉴权 | 搜索需 buvid3 会话 + wbi；弹幕无需 |
| 海外 | UGC 可用；PGC 视标题授权（实测部分可、部分空）；可选 QR 登录 `passport.bilibili.com/x/passport-login/web/qrcode/{generate,poll}` |
| 难度 | 中（已完成） |

---

## 2. tencent 腾讯视频（v1.0.9）

| 项 | 值 |
|---|---|
| 搜索 | `POST https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vplatform=2`（备：`.../HttpMobileRecall/MbSearchHttp`） |
| 分集 | `POST https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vversion_name=8.2.96&vversion_platform=2`（body 带 `page_params`/`page_type`/`detail_page_type=1`）→ vid 列表 |
| **弹幕索引** | `GET https://dm.video.qq.com/barrage/base/{vid}` → 返回分段表（`segment_index` / `segment_name`） |
| **弹幕分段** | `GET https://dm.video.qq.com/barrage/segment/{vid}/{segment_name}` |
| 格式 | JSON（base 给段清单，逐段 JSON） |
| 鉴权 | web barrage 接口基本公开，无强签名；搜索/取 vid 走 trpc POST |
| 难度 | 中（两步：base 索引 → segment 循环）；**华语影视主力，优先级高** |

---

## 3. iqiyi 爱奇艺（v1.1.0）

| 项 | 值 |
|---|---|
| 搜索 | `GET https://search.video.iqiyi.com/o?if=html5&key={kw}`（备 `mesh.if.iqiyi.com/portal/lw/search/homePageV3`） |
| 分集/时长 | `pcw-api.iqiyi.com/albums/album/avlistinfo`、`/video/video/baseinfo/`、`www.iqiyi.com/prelw/tvg/v2/lw/base_info?` → **tvid** + duration（`_get_duration_for_tvid`） |
| **弹幕** | `GET https://cmts.iqiyi.com/bullet/{tvid 后两位}/{...}/{tvid}_300_{mat}.z`（按 **tvid + mat** 分段） |
| 分段 | `mat` = 5min 段；段数 = ceil(duration/300s)（`_get_danmu_content_by_mat`） |
| 格式 | **protobuf + gzip**（`.z` 后缀 gzip；`_download_and_parse_segment_protobuf` / `_pb_parse_bullet`）；URL 含 `_generate_hash_protobuf` 生成的校验 |
| 难度 | 高（protobuf + gzip + hash + tvid 路径前缀分桶）；**主力但偏难** |

---

## 4. youku 优酷（v1.1.5）

| 项 | 值 |
|---|---|
| 搜索 | `GET https://search.youku.com/api/search?` |
| 分集 | `https://openapi.youku.com/v2/shows/videos.json?client_id=53e6cc67237fc59a&...&show_id=`、`/v2/videos/show_basic.json?...&video_id=` → vid |
| **弹幕** | `GET https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/?`（mtop 接口，按 **mat** 分段） |
| 鉴权 | **mtop 签名链**：先 `https://acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/?jsv=2.5.1&appKey=24679788` 拿 `cna` + `_m_h5_tk` token cookie；请求体 `msg` + `sign = md5(token & t & appKey & msg)` |
| 格式 | JSON（`YoukuComment` / `YoukuCommentProperty`） |
| 难度 | 高（mtop `_m_h5_tk` 令牌+签名是主要门槛） |

---

## 5. mgtv 芒果TV（v1.1.6）

| 项 | 值 |
|---|---|
| 搜索 | `GET https://mobileso.bz.mgtv.com/msite/search/v2?q={kw}` |
| 分集/时长 | `https://pcweb.api.mgtv.com/variety/showlist?collection_id=`、`/video/info?cid={cid}&allowedRC=1` → vid + 时长 |
| **弹幕** | `GET https://galaxy.bz.mgtv.com/rdbarrage?vid={vid}&time={ms}`（主）；`opbarrage`、`getctlbarrage`（控评/运营弹幕） |
| 分段 | 按 `time` 毫秒，每段 60000ms；段数 = 时长/60s（`progress_callback` 逐段） |
| 格式 | JSON（`MgtvCommentSegmentData` / `MgtvCommentColorRGB`） |
| 海外 | **search 海外 403（实测）→ 需代理** |
| 难度 | 中（纯 JSON 友好；卡在海外封锁）；**配代理后优先级高** |

---

## 6. migu 咪咕视频（v1.1.4）

| 项 | 值 |
|---|---|
| 搜索 | `https://jadeite.migu.cn/search/v3/open-search` |
| 分集 | `https://v3-sc.miguvideo.com/program/v4/cont/content-info/{content_id}/1` |
| **弹幕** | `GET https://webapi.miguvideo.com/gateway/live_barrage/videox/barrage/v2/list/{content_id}/{episode_id}/{start}/{end}/{platid}`（platid=020） |
| 分段 | 按 `start/end` 时间窗，`total_segments` |
| 鉴权 | **WASM 网关签名**：`https://www.miguvideo.com/mgs/common/miguvendor/prd/gateway-crypto.wasm`（需在端上执行 WASM 生成网关加密参数） |
| 难度 | 很高（WASM 签名，客户端难复刻）；**暂不建议** |

---

## 7. sohu 搜狐视频（v1.1.1）

| 项 | 值 |
|---|---|
| 搜索 | `https://m.so.tv.sohu.com/search/pc/keyword` |
| 分集 | `https://pl.hd.sohu.com/videolist`；页面正则 `var playlistId = (\d+)` → aid/vid |
| **弹幕** | `GET https://api.danmu.tv.sohu.com/dmh5/dmListAll?aid={aid}&vid={vid}&time=...&api_key=...` |
| 分段 | 按 time，`segment_duration`、`total_segments` |
| 格式 | JSON（`SohuDanmuResponse` / `SohuComment`） |
| 难度 | 中 |

---

## 8. le 乐视视频（v1.0.9）

| 项 | 值 |
|---|---|
| 搜索 | `https://so.le.com/s` |
| 分集 | `www.le.com/ptv/vplay/`、`/tv/`、`/movie/` 等 → vid/danmu_id |
| **弹幕** | `GET https://hd-my.le.com/danmu/list?...`（`danmu_id`，分段 `segments`） |
| 格式 | JSON（`LetvDanmuResponse`），`Accept-Encoding: gzip, deflate, br` |
| 难度 | 中 |

---

## 9. xigua 西瓜视频（v1.1.2）

| 项 | 值 |
|---|---|
| 搜索 | `https://search.ixigua.com/video/app/search/long_video/` |
| 分集 | `https://ib.snssdk.com/vapp/lvideo/api/info/` → album/episode |
| **弹幕** | `GET https://ib.snssdk.com/vapp/danmaku/list/v1/` |
| 格式 | **protobuf** |
| 鉴权 | 头条系 APP 公共参数（device_id/iid 等） |
| 难度 | 中-高（protobuf + 头条 APP 参数） |

---

## 10. renren 人人（人人视频/影视，v1.1.1）

| 项 | 值 |
|---|---|
| 搜索 | `https://api.rrmj.plus`（关键词搜剧） |
| 分集 | `https://rrsp.com.cn/pc/drama/{dramaId}` → 集 sid（`_episode_count_from_sid`） |
| **弹幕** | `GET https://static-dm.rrmj.plus/v1/produce/danmu/EPISODE/{episodeSid}`；APP/TV 两套 host（`APP_DANMU_HOST` / `TV_DANMU_HOST`） |
| 解密 | **AES-ECB-PKCS7 + base64**，密钥明文常量 `AES_KEY = ES513W0B1CsdUrR13Qk5EgDAKPeeKZY` |
| 难度 | 中（密钥已知，解密直接可复刻） |

---

## 11. gamer 巴哈姆特动画疯（v1.0.8）— 台湾，繁中/日番

| 项 | 值 |
|---|---|
| 搜索 | `https://ani.gamer.com.tw/search.php` |
| 分集 | `animeRef.php?sn=`、`animeVideo.php?sn=` → **sn**（视频编号） |
| **弹幕** | `POST https://ani.gamer.com.tw/ajax/danmuGet.php`（body `sn={sn}`） |
| 格式 | JSON |
| 特殊 | **Cloudflare 盾 → 需 FlareSolverr（`http://localhost:8191`）** |
| 难度 | 中（逻辑简单，但依赖 FlareSolverr） |

---

## 12. hanjutv 韩剧TV（v1.1.9）

| 项 | 值 |
|---|---|
| 域名 | `api.xiawen.tv`、`hxqapi.hiyun.tv`、`hxqapi.zmdcq.com` |
| 弹幕 | `hxqapi.../danmu`（`bulletchats`，`_fetch_danmaku_raw`） |
| 解密 | **AES-128-CBC-PKCS7 + base64，密钥动态派生**：`di = AES(uid, UK_KEY, UK_IV)`，`rp = AES(payload, uidMd5[:16], uidMd5[16:32])` |
| 难度 | 高（动态密钥派生，需先逆 UK_KEY/UK_IV 常量） |

---

## 13. ezdmw（v1.0.1）— 通用/聚合源

| 项 | 值 |
|---|---|
| 域名 | `player.ezdmw.com`、`www.ezdmw.site` |
| 弹幕 | `danmaku_url` → `_parse_danmaku_xml` |
| 格式 | **XML**（可直接复用 `BiliDanmaku.parseXml` 同款 `<d p=>`） |
| 难度 | 低 |

---

## 14. mddcloud（v1.0.7）

| 项 | 值 |
|---|---|
| 域名 | `mob.mddcloud.com.cn`、`www.mddcloud.com.cn` |
| 弹幕 | `GET /api/barrage/vodBarrage396.action`（`barrageList`） |
| 分段 | `segment_start` / `segment_end` / `segment_duration` |
| 格式 | JSON |
| 难度 | 低-中 |

---

## 15. dandanplay 弹弹play（v1.1.3）— 番剧聚合

| 项 | 值 |
|---|---|
| 域名 | `api.dandanplay.net` |
| 弹幕 | 标准弹弹play `/api/v2/comment/{episodeId}` |
| 注意 | **官方 API 现需 AppId + 签名**（本项目实测裸打 403 `Missing Authentication Headers`），且华语影视覆盖近零 |
| 难度 | 低（协议简单），但**需申请 AppId**，对华语片库价值低 |

---

## 实现优先级建议（华语影视为主的 Selene-TV）

| 优先级 | 源 | 理由 |
|---|---|---|
| ✅ 已完成 | bilibili | 已实测，番剧/影视/UGC 都能出 |
| 🥇 高 | **tencent** | 华语影视主力，JSON 两步式，无强签名 |
| 🥇 高 | **mgtv** | 纯 JSON 最友好；仅需配代理解海外封锁 |
| 🥈 中 | sohu / le / mddcloud / ezdmw | JSON/XML 简单，覆盖补充 |
| 🥈 中 | renren | AES key 已知，影视剧覆盖好 |
| 🥉 偏难 | iqiyi / xigua | protobuf + hash/APP 参数 |
| 🥉 难 | youku | mtop `_m_h5_tk` 签名链 |
| ⛔ 暂缓 | migu(WASM 签名) / hanjutv(动态密钥) / gamer(需 FlareSolverr) / dandanplay(需 AppId) | 门槛高或价值低 |

## 落地注意

1. **本文档端点除 bilibili 外均为 strings 提取，未抓包验证**。实现某源前，先用该源的真实 id 跑通 `搜索→分集→弹幕` 一遍（参照本项目验证 bilibili 的方式：host 端 curl/python 先打通，再写 Kotlin）。
2. 多数源是**按时间分段**循环，注意：① 段数 = 时长/段长，需先拿到时长；② 末段空即终止；③ 设上限防失控（参考 `BilibiliDanmakuSource.MAX_SEGMENTS`）。
3. protobuf 源（iqiyi/xigua）可扩展现有 `BiliDmProto` 的手写 wire-format 解码思路，按各自 `.proto` 字段号映射。
4. 需签名/加密/代理的源（youku/migu/hanjutv/gamer），与「远程 JS 脚本下发」架构（见 [danmaku-architecture 记忆 / Phase 1+]）天然契合——把这些易变/复杂逻辑放脚本层，而非编进 APK。
