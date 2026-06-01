// dandanplay 弹弹play 弹幕源 —— 骨架（需 AppId 签名 + 华语覆盖近零，暂不实现）。番剧聚合。
// 端点（misaka dandanplay.so / 官方文档）：
//   弹幕  GET https://api.dandanplay.net/api/v2/comment/{episodeId}?withRelated=true
//   搜索  /api/v2/search/episodes?anime={kw}
//
// ⛔ 阻塞点：官方 API 现需 AppId + 签名头
//   （X-AppId / X-Timestamp / X-Signature = base64(hmacSha256(AppId + Timestamp + Path, AppSecret))）。
//   本项目实测裸打 403 "Missing Authentication Headers"。AppSecret 须向官方申请、不可硬编进开源仓库。
//   且 dandanplay 以日番为主，对华语影视片库覆盖近零，性价比低 → 占位。
//   若日后支持：设置页让用户填自己的 AppId/AppSecret，签名用 host.hmacBase64("sha256", AppId+ts+path, secret)。
var DDP_API = "https://api.dandanplay.net";
function search(keyword) { host.log("dandanplay 未实现（需 AppId 签名）: " + keyword); return "[]"; }
function getEpisodes(mediaId) { host.log("dandanplay 未实现（需 AppId 签名）: " + mediaId); return "[]"; }
function getComments(episodeId) { host.log("dandanplay 未实现（需 AppId 签名）: " + episodeId); return "[]"; }
