// mddcloud 弹幕源 —— 骨架（端点/常量已逆，签名未逆出，暂不可用）。
// 来源：misaka mddcloud.so（Nuitka）strings 提取。
//
// 端点（POST application/json）：
//   搜索  https://www.mddcloud.com.cn/searchApi/search/getAllSearchResult4820.action
//   分集  https://www.mddcloud.com.cn/api/vod/listVodSactions.action
//   弹幕  https://mob.mddcloud.com.cn/api/barrage/vodBarrage396.action   （barrageList，按 segment_start/end/duration 分段）
//
// 已逆常量：
//   appToken      = "853BDD7A1DC011F1C341455071C03AEB"
//   appVersion    = "5.8.00"
//   sourceVersion = "1.0.7"
//   sign 模板片段 = "os:Android|version:{appVersion}" ... "|appToken:{appToken}|privateKey:{privateKey}"
//   候选 privateKey = "ae1be6b4cf4021b3d181170d1879a530a9e4130b"（40-hex，未确认）
//
// ⛔ 阻塞点：请求体需 `jsonSign`（对排序后参数 + 上述模板做摘要），但 .so 里 `_build_payload` /
//   `jsonSign` 的确切拼接顺序与摘要算法（md5? 是否含 timestamp/nonce）未能从 strings 还原，
//   且本机无法抓包验证。强行拼装大概率签名不过 → 这里仅占位，契约函数安全返回 []。
//   待真机抓一次合法请求核对 jsonSign 后再实现（届时复用 host.md5 / host.hmac）。
var MDD_PROVIDER = "mddcloud";
var MDD_WEB = "https://www.mddcloud.com.cn";
var MDD_MOB = "https://mob.mddcloud.com.cn";
var MDD_APP_TOKEN = "853BDD7A1DC011F1C341455071C03AEB";
var MDD_APP_VERSION = "5.8.00";
var MDD_SOURCE_VERSION = "1.0.7";

function search(keyword) { host.log("mddcloud search 未实现（jsonSign 未逆）: " + keyword); return "[]"; }
function getEpisodes(mediaId) { host.log("mddcloud getEpisodes 未实现（jsonSign 未逆）: " + mediaId); return "[]"; }
function getComments(episodeId) { host.log("mddcloud getComments 未实现（jsonSign 未逆）: " + episodeId); return "[]"; }
