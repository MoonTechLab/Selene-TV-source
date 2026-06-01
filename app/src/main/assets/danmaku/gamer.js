// gamer 巴哈姆特动画疯 弹幕源 —— 骨架（依赖 FlareSolverr，暂不实现）。台湾，繁中/日番。
// 端点（misaka gamer.so strings）：
//   搜索  https://ani.gamer.com.tw/search.php
//   分集  animeRef.php?sn= / animeVideo.php?sn=  → sn（视频编号）
//   弹幕  POST https://ani.gamer.com.tw/ajax/danmuGet.php   body: sn={sn}   响应 JSON
//
// ⛔ 阻塞点：站点有 Cloudflare 盾（JS challenge），裸 OkHttp 请求会被挡。
//   misaka 通过 FlareSolverr（http://localhost:8191）过盾——TV 端无该服务，纯客户端无法稳定过盾 → 占位。
//   若日后必须支持：需内置 WebView 执行 CF challenge 取 cf_clearance + UA，再用同一指纹请求。
//   逻辑本身简单（拿到 sn 直接 POST danmuGet.php），过盾是唯一门槛。
function search(keyword) { host.log("gamer 未实现（Cloudflare 盾/需 FlareSolverr）: " + keyword); return "[]"; }
function getEpisodes(mediaId) { host.log("gamer 未实现（Cloudflare 盾/需 FlareSolverr）: " + mediaId); return "[]"; }
function getComments(episodeId) { host.log("gamer 未实现（Cloudflare 盾/需 FlareSolverr）: " + episodeId); return "[]"; }
