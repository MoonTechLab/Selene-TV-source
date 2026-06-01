// 爱奇艺弹幕源（逆向验证：狂飙端到端）。
// 关键：弹幕 .z 是 zlib 包裹的 XML（非 protobuf！），用 host.inflate(b64,false) 解。
// search→avlistinfo→baseinfo(时长)→cmts.iqiyi.com/bullet/{s1}/{s2}/{tvid}_300_{mat}.z
// 契约返回 JSON：mode 统一 1（爱奇艺无干净顶/底枚举），color 十进制 RGB。
var IQ_UA = '{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}';

function _xf(block, tag) { var m = new RegExp("<" + tag + ">([\\s\\S]*?)</" + tag + ">").exec(block); return m ? m[1] : null; }
function _cp(n) { // codePoint -> string（手写，兼容无 fromCodePoint 的引擎）
  if (n <= 0xFFFF) return String.fromCharCode(n);
  n -= 0x10000; return String.fromCharCode(0xD800 + (n >> 10), 0xDC00 + (n & 0x3FF));
}
function _unesc(s) {
  if (s == null) return "";
  return s.replace(/&#x([0-9a-fA-F]+);/g, function (_, h) { return _cp(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return _cp(parseInt(d, 10)); })
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}
function _bucket(tvid) { var s = String(tvid); return { s1: s.slice(-4, -2), s2: s.slice(-2) }; }
function _hexDec(h) { var n = parseInt(String(h || "ffffff"), 16); return isNaN(n) ? 16777215 : n; }

function search(keyword) {
  var out = [];
  try {
    var obj = JSON.parse(host.httpGet("https://search.video.iqiyi.com/o?if=html5&key=" + encodeURIComponent(keyword), IQ_UA));
    var docs = (obj.data && obj.data.docinfos) || [];
    for (var i = 0; i < docs.length; i++) {
      var a = docs[i].albumDocInfo;
      if (!a || a.siteId !== "iqiyi") continue;
      var channelId = (a.channel || "").split(",")[1] || "";
      out.push({ provider: "iqiyi", mediaId: String(a.albumId), title: a.albumTitle,
        type: channelId === "1" ? "movie" : "tv", year: (a.releaseDate || "").slice(0, 4) });
    }
  } catch (e) { host.log("iqiyi search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var out = [];
  try {
    var obj = JSON.parse(host.httpGet("https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=" + encodeURIComponent(mediaId) + "&page=1&size=60", IQ_UA));
    var list = (obj.data && obj.data.epsodelist) || [];
    if (list.length === 0) { out.push({ provider: "iqiyi", episodeId: String(mediaId), title: "正片", episodeIndex: 1 }); return JSON.stringify(out); }
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      out.push({ provider: "iqiyi", episodeId: String(e.tvId), title: e.name, episodeIndex: e.order });
    }
  } catch (e) { host.log("iqiyi eps err: " + e); }
  return JSON.stringify(out);
}

function getComments(episodeId) {
  var tvid = String(episodeId), comments = [];
  var durSec = 0;
  try { var b = JSON.parse(host.httpGet("https://pcw-api.iqiyi.com/video/video/baseinfo/" + tvid, IQ_UA)); durSec = (b.data && b.data.durationSec) ? b.data.durationSec : 0; } catch (e) {}
  var maxMat = durSec > 0 ? Math.ceil(durSec / 300) : 100;
  var bk = _bucket(tvid);
  for (var mat = 1; mat <= maxMat; mat++) {
    var u = "https://cmts.iqiyi.com/bullet/" + bk.s1 + "/" + bk.s2 + "/" + tvid + "_300_" + mat + ".z";
    var b64; try { b64 = host.httpGetBytes(u, IQ_UA); } catch (e) { break; }
    if (!b64) break;
    var xml; try { xml = host.inflate(b64, false); } catch (e) { continue; }
    if (!xml) continue;
    var re = /<bulletInfo>([\s\S]*?)<\/bulletInfo>/g, m;
    while ((m = re.exec(xml)) !== null) {
      var blk = m[1];
      var st = _xf(blk, "showTime"); if (st == null) continue;
      var content = _unesc(_xf(blk, "content")); if (!content) continue;
      comments.push({ timeMs: parseInt(st, 10) * 1000, mode: 1, color: _hexDec(_xf(blk, "color") || "ffffff"), content: content });
    }
  }
  return JSON.stringify(comments);
}
