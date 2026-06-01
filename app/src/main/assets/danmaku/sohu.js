// 搜狐视频弹幕源（端点已验证，但海外搜索被封 + 现存内容弹幕几乎为空，属低价值）。
// search: m.so.tv.sohu.com（海外返 is_empty）；getEpisodes: videolist(GBK)；getComments: dmListAll(request_from=h5_js 必传, 秒分段)。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
function _sj(s) { try { return JSON.parse(s); } catch (e) { return null; } }
function _cidType(cid) { cid = String(cid); if (cid === "2") return "tv"; if (cid === "1") return "movie"; if (cid === "16" || cid === "100") return "anime"; if (cid === "7" || cid === "8") return "show"; return "tv"; }
function _sohuMode(m) { m = parseInt(m, 10); if (m === 4) return 5; if (m === 5) return 4; return 1; }
function _sohuColor(c) { if (typeof c === "string") { var n = parseInt(c.replace(/^#/, ""), 16); return isNaN(n) ? 16777215 : n; } var v = parseInt(c, 10); return isNaN(v) ? 16777215 : (v & 0xFFFFFF); }

function search(keyword) {
  var out = [];
  try {
    var j = _sj(host.httpGet("https://m.so.tv.sohu.com/search/pc/keyword?q=" + encodeURIComponent(keyword), '{"User-Agent":"Mozilla/5.0","Referer":"https://m.tv.sohu.com/"}'));
    if (!j || !j.data || j.data.is_empty) return JSON.stringify(out);
    var items = j.data.items || j.data.list || j.data.albums || [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i], pl = it.playlistId || it.playlistid || it.pl || it.aid; if (!pl) continue;
      out.push({ provider: "sohu", mediaId: String(pl), title: (it.albumName || it.tvName || it.name || "").replace(/<[^>]+>/g, ""), type: _cidType(it.cid), year: String(it.year || it.publishYear || it.tvYear || "") });
    }
  } catch (e) { host.log("sohu search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var all = [], pid = null;
  try {
    for (var page = 1; page <= 20; page++) {
      var j = _sj(host.httpGetGbk("https://pl.hd.sohu.com/videolist?playlistid=" + mediaId + "&pagesize=60&pagenum=" + page + "&order=1&pl=2", '{"User-Agent":"Mozilla/5.0","Referer":"https://tv.sohu.com/"}'));
      if (!j || !j.videos || j.videos.length === 0) break;
      if (pid === null) pid = j.pid;
      for (var i = 0; i < j.videos.length; i++) { var v = j.videos[i]; all.push({ provider: "sohu", episodeId: pid + ":" + v.vid, title: v.name || ("第" + v.order + "集"), episodeIndex: parseInt(v.order, 10) || (all.length + 1) }); }
      if (j.videos.length < 60) break;
    }
  } catch (e) { host.log("sohu eps err: " + e); }
  all.sort(function (a, b) { return a.episodeIndex - b.episodeIndex; });
  return JSON.stringify(all);
}

function getComments(episodeId) {
  var parts = episodeId.split(":"), aid = parts[0], vid = parts[1], out = [], SEG = 300, empty = 0;
  try {
    for (var s = 0; s < SEG * 60; s += SEG) {
      var j = _sj(host.httpGet("https://api.danmu.tv.sohu.com/dmh5/dmListAll?aid=" + aid + "&vid=" + vid + "&time_begin=" + s + "&time_end=" + (s + SEG) + "&request_from=h5_js", '{"User-Agent":"Mozilla/5.0","Referer":"https://tv.sohu.com/"}'));
      if (!j || j.status !== 1 || !j.info) break;
      var arr = j.info.comments || j.info.danmu || j.info.list || [];
      if (arr.length === 0) { empty++; if (empty >= 3) break; continue; }
      empty = 0;
      for (var i = 0; i < arr.length; i++) {
        var c = arr[i], tSec = (c.v != null ? c.v : (c.t != null ? c.t : c.time));
        out.push({ timeMs: Math.round(parseFloat(tSec) * 1000), mode: _sohuMode(c.mode != null ? c.mode : c.p), color: _sohuColor(c.co != null ? c.co : (c.color != null ? c.color : 16777215)), content: c.c != null ? c.c : (c.content || "") });
      }
    }
  } catch (e) { host.log("sohu comments err: " + e); }
  return JSON.stringify(out);
}
