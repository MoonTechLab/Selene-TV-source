// 乐视视频弹幕源（逆向验证：甄嬛传 9006 条）。
// search HTML(GBK) 内嵌 vidEpisode → getEpisodes 零网络；getComments: danmu/list?vid&start&end(秒, 单次≤1000 需拆窗)。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
function _f(s, key) { var m = new RegExp(key + ":'([^']*)'").exec(s); return m ? m[1] : null; }
function _leType(t) { if (t === "tv") return "tv"; if (t === "movie" || t === "film") return "movie"; if (t === "cartoon" || t === "comic") return "anime"; if (t === "zongyi" || t === "variety") return "show"; return t || "tv"; }
function _leMode(p) { p = parseInt(p, 10); if (p === 1) return 5; if (p === 3) return 4; return 1; } // 2,4=滚动
function _leColor(c) { var n = parseInt(String(c || "FFFFFF").replace(/^#/, ""), 16); return isNaN(n) ? 16777215 : n; }

function search(keyword) {
  var out = [];
  try {
    var html = host.httpGetGbk("https://so.le.com/s?wd=" + encodeURIComponent(keyword), '{"User-Agent":"Mozilla/5.0"}');
    var re = /data-info="\{([^"]*?)\}"/g, m;
    while ((m = re.exec(html)) !== null) {
      var info = m[1], pid = _f(info, "pid"), type = _f(info, "type"), ve = _f(info, "vidEpisode");
      if (!pid || !ve) continue;
      var after = html.substr(m.index + m[0].length, 500), tm = /title="([^"]+)"/.exec(after);
      var kw = _f(info, "keyWord") || "", ym = /((19|20)\d{2})/.exec(kw);
      out.push({ provider: "letv", mediaId: pid + "|" + ve, title: tm ? tm[1] : kw, type: _leType(type), year: ym ? ym[1] : "" });
    }
  } catch (e) { host.log("letv search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var ve = mediaId.substr(mediaId.indexOf("|") + 1), pairs = ve.split(","), out = [];
  for (var i = 0; i < pairs.length; i++) {
    var p = pairs[i].split("-"); if (p.length < 2) continue;
    out.push({ provider: "letv", episodeId: p[1], title: "第" + parseInt(p[0], 10) + "集", episodeIndex: parseInt(p[0], 10) });
  }
  out.sort(function (a, b) { return a.episodeIndex - b.episodeIndex; });
  return JSON.stringify(out);
}

function _leWindow(vid, a, b) {
  var raw = host.httpGet("https://hd-my.le.com/danmu/list?vid=" + vid + "&start=" + a + "&end=" + b, '{"User-Agent":"Mozilla/5.0","Referer":"https://www.le.com/"}');
  var j; try { j = JSON.parse(raw); } catch (e) { return null; }
  if (!j || !j.data || !j.data.list) return [];
  return j.data.list;
}
function _leSplit(out, vid, a, b) {
  if (b - a <= 10) { var lst = _leWindow(vid, a, b) || []; for (var i = 0; i < lst.length; i++) out.push(_leMap(lst[i])); return; }
  var mid = Math.floor((a + b) / 2);
  var left = _leWindow(vid, a, mid) || []; if (left.length >= 1000) _leSplit(out, vid, a, mid); else for (var i = 0; i < left.length; i++) out.push(_leMap(left[i]));
  var right = _leWindow(vid, mid, b) || []; if (right.length >= 1000) _leSplit(out, vid, mid, b); else for (var k = 0; k < right.length; k++) out.push(_leMap(right[k]));
}
function _leMap(c) { return { timeMs: Math.round(parseFloat(c.start) * 1000), mode: _leMode(c.position), color: _leColor(c.color), content: c.txt || "" }; }
function getComments(episodeId) {
  var vid = episodeId, out = [], SEG = 300, empty = 0;
  for (var s = 0; s < SEG * 60; s += SEG) {
    var raw = _leWindow(vid, s, s + SEG);
    if (raw == null) break;
    if (raw.length === 0) { empty++; if (empty >= 2) break; continue; }
    empty = 0;
    if (raw.length >= 1000) _leSplit(out, vid, s, s + SEG); else for (var i = 0; i < raw.length; i++) out.push(_leMap(raw[i]));
  }
  return JSON.stringify(out);
}
