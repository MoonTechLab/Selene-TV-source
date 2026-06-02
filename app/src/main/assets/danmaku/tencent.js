// 腾讯视频弹幕源（逆向验证：庆余年端到端）。
// search: POST MbSearch；getEpisodes: POST GetPageData(分页 tabs)；getComments: barrage base→segment(JSON)。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
var QQ_HEADERS = JSON.stringify({
  "Content-Type": "application/json", "Origin": "https://v.qq.com", "Referer": "https://v.qq.com/",
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
});
var DM_HEADERS = JSON.stringify({ "Referer": "https://v.qq.com/", "User-Agent": "Mozilla/5.0" });

function stripEm(s) { return s ? s.replace(/<\/?em>/gi, "") : ""; }
function hexToDec(hex) {
  if (!hex) return 16777215;
  hex = ("" + hex).replace(/^#/, "");
  if (hex.length === 3) hex = hex.charAt(0)+hex.charAt(0)+hex.charAt(1)+hex.charAt(1)+hex.charAt(2)+hex.charAt(2);
  var n = parseInt(hex, 16); return isNaN(n) ? 16777215 : n;
}

function search(keyword) {
  var body = JSON.stringify({ query: keyword, version: "", retry: 0, grp: 1, preQid: "", pagenum: 0, pagesize: 30,
    queryFrom: 0, isneedQc: true, adClientInfo: "", extraInfo: { isNewMarkLabel: "1", multi_terminal_pc: "1", themeType: "1" } });
  var url = "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.MultiTerminalSearch/MbSearch?vplatform=2";
  var out = [];
  try {
    var json = JSON.parse(host.httpPost(url, body, QQ_HEADERS));
    if (!json || json.ret !== 0 || !json.data) return JSON.stringify(out);
    // 正片在 normalList，areaBoxList 多为「相关影视」派生 box——两者都收，normalList 优先。
    var items = [];
    var nl = (json.data.normalList && json.data.normalList.itemList) || [];
    for (var n = 0; n < nl.length; n++) items.push(nl[n]);
    var boxes = json.data.areaBoxList || [];
    for (var b = 0; b < boxes.length; b++) {
      var bi = boxes[b].itemList || [];
      for (var bj = 0; bj < bi.length; bj++) items.push(bi[bj]);
    }
    {
      for (var i = 0; i < items.length; i++) {
        var it = items[i], doc = it.doc || {}, vi = it.videoInfo;
        if (!vi || !doc.id) continue;
        if (doc.dataType !== undefined && doc.dataType !== 2) continue;
        var sites = vi.playSites || vi.episodeSites || [];
        if (!sites.length) continue;
        // 只收腾讯自有站点(enName 'qq')的正片：搜索索引会把异网独播（如爱奇艺独播 'qiyi'）也列出，
        // 但腾讯并不托管其播放/弹幕——这种 cid 在 GetPageData 取不到分集，会误占默认源(优先级高于爱奇艺)且零弹幕。
        var onQQ = false;
        for (var si = 0; si < sites.length; si++) { if (sites[si] && sites[si].enName === "qq") { onQQ = true; break; } }
        if (!onQQ) continue;
        out.push({ provider: "tencent", mediaId: doc.id, title: stripEm(vi.title),
          type: vi.typeName || "", year: (vi.year && vi.year > 0) ? vi.year : 0 });
      }
    }
  } catch (e) { host.log("tencent search err: " + e); }
  return JSON.stringify(out);
}

function _getPageData(cid, ctx) {
  var body = JSON.stringify({ page_params: { req_from: "web_vsite", page_id: "vsite_episode_list",
    page_type: "detail_operation", id_type: "1", page_size: "", cid: cid, vid: "", lid: "", page_num: "",
    page_context: ctx || "", detail_page_type: "1" }, has_cache: 1 });
  var url = "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vversion_name=8.2.96&vversion_platform=2";
  return JSON.parse(host.httpPost(url, body, QQ_HEADERS));
}
function _extractModule(json) {
  if (!json || json.ret !== 0 || !json.data) return null;
  var mld = json.data.module_list_datas || []; if (!mld.length) return null;
  var mds = mld[0].module_datas || []; if (!mds.length) return null;
  return mds[0];
}
function _collectEpisodes(mod, acc, startIdx) {
  var idx = startIdx, lists = (mod.item_data_lists || {}).item_datas || [];
  for (var i = 0; i < lists.length; i++) {
    var p = lists[i].item_params || {};
    if (!p.vid || !p.play_title) continue;
    idx++;
    acc.push({ provider: "tencent", episodeId: p.vid, title: p.play_title || p.title || ("第" + idx + "集"), episodeIndex: idx });
  }
  return idx;
}
function getEpisodes(mediaId) {
  var out = [];
  try {
    var first = _getPageData(mediaId, ""); var mod = _extractModule(first);
    if (!mod) return JSON.stringify(out);
    var tabs = [];
    try { var mp = mod.module_params || {}; if (mp.tabs) tabs = JSON.parse(mp.tabs); } catch (e) { tabs = []; }
    var idx = 0;
    if (tabs.length > 0) {
      for (var t = 0; t < tabs.length; t++) {
        var tab = tabs[t], modT;
        if (tab.selected) { modT = mod; } else { modT = _extractModule(_getPageData(mediaId, tab.page_context || "")); if (!modT) continue; }
        idx = _collectEpisodes(modT, out, idx);
      }
    } else { idx = _collectEpisodes(mod, out, idx); }
  } catch (e) { host.log("tencent eps err: " + e); }
  return JSON.stringify(out);
}

function _mapMode(p) { if (p === 2) return 5; if (p === 3) return 4; return 1; }
function getComments(episodeId) {
  var out = [];
  try {
    var base = JSON.parse(host.httpGet("https://dm.video.qq.com/barrage/base/" + episodeId, DM_HEADERS));
    var index = base.segment_index || {};
    var keys = []; for (var k in index) { if (index.hasOwnProperty(k)) keys.push(k); }
    keys.sort(function (a, b) { return parseInt(a, 10) - parseInt(b, 10); });
    for (var s = 0; s < keys.length; s++) {
      var segName = index[keys[s]].segment_name; if (!segName) continue;
      var seg; try { seg = JSON.parse(host.httpGet("https://dm.video.qq.com/barrage/segment/" + episodeId + "/" + segName, DM_HEADERS)); } catch (e) { continue; }
      var list = seg.barrage_list || [];
      for (var i = 0; i < list.length; i++) {
        var bb = list[i];
        var timeMs = parseInt(bb.time_offset, 10); if (isNaN(timeMs)) timeMs = 0;
        var mode = 1, color = 16777215;
        if (bb.content_style && bb.content_style !== "") {
          try { var cs = JSON.parse(bb.content_style); mode = _mapMode(cs.position);
            if (cs.gradient_colors && cs.gradient_colors.length) color = hexToDec(cs.gradient_colors[0]);
            else if (cs.color) color = hexToDec(cs.color); } catch (e3) {}
        }
        out.push({ timeMs: timeMs, mode: mode, color: color, content: bb.content || "" });
      }
    }
  } catch (e) { host.log("tencent comments err: " + e); }
  return JSON.stringify(out);
}
