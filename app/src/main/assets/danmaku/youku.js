// 优酷弹幕源（逆向验证：mtop 签名链，387条）。
// 难点 mtop 签名：先 GET weakget（CookieJar 自动存 _m_h5_tk/_m_h5_tk_enc），
// token = _m_h5_tk 按 _ 分割首段；sign = md5(token & t & appKey & dataJSON)；POST danmu.list（jar 自动回传两 cookie）。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
var YK_APPKEY = "24679788";
var YK_H = JSON.stringify({ "User-Agent": "Mozilla/5.0", "Referer": "https://v.youku.com" });

function search(keyword) {
  var out = [];
  try {
    var j = JSON.parse(host.httpGet("https://search.youku.com/api/search?keyword=" + encodeURIComponent(keyword), YK_H));
    var comps = j.pageComponentList || [];
    for (var i = 0; i < comps.length; i++) {
      var cd = comps[i].commonData;
      if (!cd || cd.isYouku !== 1) continue;
      var feat = cd.feature || "", ym = /(\d{4})/.exec(feat);
      out.push({ provider: "youku", mediaId: cd.realShowId || cd.showId, title: (cd.titleDTO && cd.titleDTO.displayName) || "",
        type: feat.indexOf("电影") >= 0 ? "movie" : "tv", year: ym ? ym[1] : "" });
    }
  } catch (e) { host.log("youku search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var out = [];
  try {
    var j = JSON.parse(host.httpGet("https://openapi.youku.com/v2/shows/videos.json?client_id=53e6cc67237fc59a&package=com.huawei.hwvplayer.youku&ext=show&show_id=" + mediaId, YK_H));
    var vids = j.videos || [];
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i], durSec = Math.ceil(parseFloat(v.duration || "0"));
      out.push({ provider: "youku", episodeId: v.id + "|" + durSec, title: v.title || ("第" + v.stage + "集"), episodeIndex: parseInt(v.seq || v.stage || "0", 10) });
    }
  } catch (e) { host.log("youku eps err: " + e); }
  return JSON.stringify(out);
}

function _ykToken() {
  host.httpGet("https://acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/?jsv=2.5.1&appKey=" + YK_APPKEY, YK_H);
  var tk = host.cookie("https://acs.youku.com", "_m_h5_tk");
  return tk ? tk.split("_")[0] : "";
}
function _ykFetch(vid, mat, token, retry) {
  var t = String((new Date()).getTime());
  var dataStr = JSON.stringify({ vid: vid, mat: mat });
  var sign = host.md5(token + "&" + t + "&" + YK_APPKEY + "&" + dataStr);
  var url = "https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/?jsv=2.7.0&appKey=" + YK_APPKEY + "&t=" + t + "&sign=" + sign +
    "&api=mopen.youku.danmu.list&v=1.0&type=originaljson&dataType=jsonp&timeout=20000";
  var hdr = JSON.stringify({ "User-Agent": "Mozilla/5.0", "Referer": "https://v.youku.com", "Content-Type": "application/x-www-form-urlencoded" });
  var resp = host.httpPost(url, "data=" + encodeURIComponent(dataStr), hdr);
  var o; try { o = JSON.parse(resp); } catch (e) { return []; }
  var ret = (o.ret && o.ret[0]) || "";
  if (ret.indexOf("SUCCESS") < 0) {
    if (!retry && ret.indexOf("TOKEN") >= 0) return _ykFetch(vid, mat, _ykToken(), true);
    return [];
  }
  try { var inner = JSON.parse(o.data.result); return (inner.data && inner.data.result) || []; } catch (e) { return []; }
}
function getComments(episodeId) {
  var parts = episodeId.split("|"), vid = parts[0], durSec = parseInt(parts[1] || "0", 10);
  var matCount = Math.max(1, Math.ceil(durSec / 60)), out = [];
  var token = _ykToken();
  for (var mat = 1; mat <= matCount; mat++) {
    var list = _ykFetch(vid, mat, token, false);
    for (var i = 0; i < list.length; i++) {
      var d = list[i], color = 16777215, pos = 3;
      try { var p = JSON.parse(d.propertis || "{}"); if (p.color != null) color = p.color; if (p.pos != null) pos = p.pos; } catch (e) {}
      var mode = pos === 1 ? 5 : (pos === 4 ? 4 : 1);
      out.push({ timeMs: d.playat, mode: mode, color: color, content: d.content });
    }
  }
  return JSON.stringify(out);
}
