// migu 咪咕视频 弹幕源 —— 骨架（客户端难复刻，暂不实现）。
// 端点（misaka migu.so strings）：
//   搜索  https://jadeite.migu.cn/search/v3/open-search
//   分集  https://v3-sc.miguvideo.com/program/v4/cont/content-info/{content_id}/1
//   弹幕  GET https://webapi.miguvideo.com/gateway/live_barrage/videox/barrage/v2/list/{cid}/{eid}/{start}/{end}/{platid}  (platid=020)
//   分段  按 start/end 时间窗，total_segments
//
// ⛔ 阻塞点：网关请求参数由 WASM 签名生成
//   （https://www.miguvideo.com/mgs/common/miguvendor/prd/gateway-crypto.wasm，需端上执行 WASM）。
//   当前 host 原语不含 WASM 运行时，纯 JS 无法复刻签名 → 占位，契约函数返回 []。
//   若日后必须支持：需在 host 侧内嵌 WASM 解释器并新增 host.wasmCall 之类原语。
function search(keyword) { host.log("migu 未实现（WASM 网关签名）: " + keyword); return "[]"; }
function getEpisodes(mediaId) { host.log("migu 未实现（WASM 网关签名）: " + mediaId); return "[]"; }
function getComments(episodeId) { host.log("migu 未实现（WASM 网关签名）: " + episodeId); return "[]"; }
