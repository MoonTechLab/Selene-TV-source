// 芒果TV弹幕源（逆向验证：与凤行端到端）。
// search: msite/search/v2；getEpisodes: variety/showlist(综艺) 或 episode/list(剧集)；
// getComments: getctlbarrage→CDN {N}.json(分钟序号,404停) 兜底 rdbarrage(time步进)。
// 契约返回 JSON：mode 1(芒果普通弹幕全滚动)，color 十进制(渐变未映射,统一白)。
// 注：mobileso 搜索接口已开始校验 UA，短 "Mozilla/5.0" 会被 401 拒；须用完整浏览器 UA。
var MG_H = JSON.stringify({ "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36", "Referer": "https://www.mgtv.com/" });

function _stripB(s) { return (s || "").replace(/<\/?B>/g, ""); }
function _durSec(t) { if (!t) return 0; var p = t.split(":"); for (var i = 0; i < p.length; i++) p[i] = parseInt(p[i], 10) || 0; return p.length === 3 ? p[0]*3600+p[1]*60+p[2] : p[0]*60+p[1]; }

function search(keyword) {
  var out = [], seen = {};
  try {
    var resp = JSON.parse(host.httpGet("https://mobileso.bz.mgtv.com/msite/search/v2?q=" + encodeURIComponent(keyword), MG_H));
    var contents = (resp.data && resp.data.contents) || [];
    for (var b = 0; b < contents.length; b++) {
      if (contents[b].type !== "media") continue;
      var arr = contents[b].data || [];
      for (var i = 0; i < arr.length; i++) {
        var it = arr[i];
        var m = /^\/b\/(\d+)\/(\d+)\.html$/.exec(it.url || ""); if (!m) continue;
        var cid = m[1]; if (seen[cid]) continue; seen[cid] = 1;
        var type = "", year = "";
        var d0 = (it.desc && it.desc[0]) || "";
        var dm = d0.replace(/^类型:\s*/, "").split("/");
        if (dm.length) { type = (dm[0] || "").replace(/\s/g, ""); var ym = /(\d{4})/.exec(dm[dm.length - 1]); if (ym) year = ym[1]; }
        out.push({ provider: "mgtv", mediaId: cid, title: _stripB(it.title), type: type, year: year });
      }
    }
  } catch (e) { host.log("mgtv search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var cid = mediaId, out = [], idx = 1;
  try {
    var probe = JSON.parse(host.httpGet("https://pcweb.api.mgtv.com/variety/showlist?collection_id=" + cid + "&page=1&_support=10000000", MG_H));
    if (probe.data && probe.data.tab_m && probe.data.tab_m.length) { // 综艺：按月聚合
      var months = probe.data.tab_m;
      for (var mi = 0; mi < months.length; mi++) {
        var r = JSON.parse(host.httpGet("https://pcweb.api.mgtv.com/variety/showlist?collection_id=" + cid + "&month=" + months[mi].m + "&page=1&_support=10000000", MG_H));
        var lst = (r.data && r.data.list) || [];
        for (var j = 0; j < lst.length; j++) { var e = lst[j]; out.push({ provider: "mgtv", episodeId: e.video_id + "|" + cid, title: e.t3 || e.t1 || ("第" + idx + "期"), episodeIndex: idx++ }); }
      }
      return JSON.stringify(out);
    }
  } catch (e) {}
  // 剧集/动漫：episode/list（page 从 0）
  try {
    var page = 0, totalPage = 1;
    do {
      var rr = JSON.parse(host.httpGet("https://pcweb.api.mgtv.com/episode/list?video_id=" + cid + "&cid=" + cid + "&page=" + page + "&size=30&allowedRC=1", MG_H));
      var list = (rr.data && rr.data.list) || []; totalPage = (rr.data && rr.data.total_page) || 1;
      for (var k = 0; k < list.length; k++) { var ee = list[k]; var n = parseInt(ee.t1, 10);
        out.push({ provider: "mgtv", episodeId: ee.video_id + "|" + cid, title: ee.t4 || ("第" + (isNaN(n) ? idx : n) + "集"), episodeIndex: isNaN(n) ? idx : n }); idx++; }
      page++;
    } while (page < totalPage);
  } catch (e) { host.log("mgtv eps err: " + e); }
  return JSON.stringify(out);
}

function _push(items, out) {
  for (var i = 0; i < items.length; i++) {
    var it = items[i]; if (it.type !== 0) continue;
    var c = (it.content || "").replace(/^\s+|\s+$/g, ""); if (!c) continue;
    out.push({ timeMs: it.time, mode: 1, color: 16777215, content: c });
  }
}
function getComments(episodeId) {
  var parts = episodeId.split("|"), vid = parts[0], cid = parts[1] || "", out = [];
  var cdnVer = "", cdnHost = "";
  try {
    var ctl = JSON.parse(host.httpGet("https://galaxy.bz.mgtv.com/getctlbarrage?version=8.1.39&abroad=0&platform=0&vid=" + vid + "&cid=" + cid, MG_H));
    if (ctl.data) { cdnVer = ctl.data.cdn_version || ""; var hosts = (ctl.data.cdn_list || "").split(","); cdnHost = hosts[0] || "bullet-ali.hitv.com"; }
  } catch (e) {}
  if (cdnVer && cdnHost) {
    for (var n = 0; n < 600; n++) {
      var raw; try { raw = host.httpGet("https://" + cdnHost + "/" + cdnVer + "/" + n + ".json", MG_H); } catch (e) { break; }
      if (!raw || raw.charAt(0) === "<" || raw.indexOf("NoSuchKey") >= 0) break;
      var seg; try { seg = JSON.parse(raw); } catch (e) { break; }
      _push((seg.data && seg.data.items) || [], out);
    }
    if (out.length) return JSON.stringify(out);
  }
  // 兜底 rdbarrage
  var durSec = 0;
  try { var info = JSON.parse(host.httpGet("https://pcweb.api.mgtv.com/video/info?cid=" + cid + "&vid=" + vid + "&allowedRC=1", MG_H)); durSec = _durSec(info.data && info.data.info && info.data.info.time); } catch (e) {}
  var segCount = durSec > 0 ? Math.ceil(durSec / 60) : 200;
  for (var s = 0; s <= segCount; s++) {
    var r; try { r = JSON.parse(host.httpGet("https://galaxy.bz.mgtv.com/rdbarrage?vid=" + vid + "&cid=" + cid + "&time=" + (s * 60000), MG_H)); } catch (e) { continue; }
    if (r.status === 202) break;
    _push((r.data && r.data.items) || [], out);
  }
  return JSON.stringify(out);
}
