// 西瓜视频弹幕源（逆向验证：1539 条，零签名）。
// 走 m.ixigua 移动站 HTML + danmaku list?format=json（绕开 protobuf 与头条签名）。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
var XG_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/17.5 Mobile/15A5370a Safari/602.1";
var XG_H = JSON.stringify({ "User-Agent": XG_UA });

function _xf1(re, s) { var m = s.match(re); return m ? m[1] : ""; }
function _xgColor(c) { if (!c) return 16777215; var n = parseInt(String(c).replace(/^0x/i, "").replace(/^#/, ""), 16); return isNaN(n) ? 16777215 : n; }
function _xgMode(d) { var pt = d.position_type || 0; if (pt === 1) return 5; if (pt === 2) return 4; return 1; }

function search(keyword) {
  var out = [], seen = {};
  try {
    var html = host.httpGet("https://m.ixigua.com/s/" + encodeURIComponent(keyword), XG_H) || "";
    var secM = html.match(/s-long-video-card([\s\S]*?)(?:<\/section>|search-section)/);
    var seg = secM ? secM[1] : html;
    var cardRe = /<div class="s-long-video">([\s\S]*?)(?=<div class="s-long-video">|$)/g, card;
    while ((card = cardRe.exec(seg)) !== null) {
      var c = card[1], mediaId = _xf1(/href="\/video\/(\d+)"/, c);
      if (!mediaId || seen[mediaId]) continue; seen[mediaId] = 1;
      var title = _xf1(/s-long-video-info-title[\s\S]*?title="([^"]+)"/, c);
      var ty = c.match(/<p>([^<]+\/[^<]+\/(\d{4}))<\/p>/);
      if (mediaId && title) out.push({ provider: "xigua", mediaId: mediaId, title: title, type: ty ? ty[1].split("/")[0] : "", year: ty ? ty[2] : "" });
    }
  } catch (e) { host.log("xigua search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var out = [];
  try {
    var html = host.httpGet("https://m.ixigua.com/video/" + mediaId, XG_H) || "";
    var m = html.match(/"episodes_list"\s*:\s*(\[[\s\S]*?\}\s*\])/);
    if (!m) return JSON.stringify(out);
    var list = JSON.parse(m[1]);
    for (var i = 0; i < list.length; i++) {
      var ep = list[i], gid = String(ep.gid), seq = ep.seq_num != null ? ep.seq_num : (i + 1);
      out.push({ provider: "xigua", episodeId: gid, title: (ep.title && ep.title.length) ? ep.title : ("第" + seq + "集"), episodeIndex: seq });
    }
  } catch (e) { host.log("xigua eps err: " + e); }
  return JSON.stringify(out);
}

function getComments(episodeId) {
  var itemId = String(episodeId), all = [];
  var durMs = 0;
  try { var page = host.httpGet("https://m.ixigua.com/video/" + itemId, XG_H) || ""; var dm = page.match(/"duration"\s*:\s*([\d.]+)/); if (dm) durMs = Math.ceil(parseFloat(dm[1]) * 1000); } catch (e) {}
  if (!durMs) durMs = 3 * 60 * 60 * 1000;
  var SEG = 300000;
  for (var s = 0; s < durMs; s += SEG) {
    var e = Math.min(s + SEG, durMs);
    var body = host.httpGet("https://ib.snssdk.com/vapp/danmaku/list/v1/?item_id=" + itemId + "&start_time=" + s + "&end_time=" + e + "&format=json", "{}");
    if (!body) continue;
    var obj; try { obj = JSON.parse(body); } catch (e2) { continue; }
    if (!obj || !obj.data) continue;
    for (var k = 0; k < obj.data.length; k++) {
      var d = obj.data[k];
      all.push({ timeMs: parseInt(d.offset_time, 10) || 0, mode: _xgMode(d), color: _xgColor(d.text_color), content: d.text || "" });
    }
  }
  return JSON.stringify(all);
}
