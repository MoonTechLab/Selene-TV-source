// B站弹幕源脚本（Phase 1：本地 assets 加载，Phase 2 改远程下发）。
// 宿主原语：host.httpGet(url, headersJson) / host.fetchDanmakuSeg(url, headersJson) / host.log(msg)。
// 三个入口函数各返回 JSON 字符串，由 Kotlin 反序列化为统一模型。
//
// 端点与 native 版一致（参照 misaka 现行 bilibili.so）：
//   search:   /x/web-interface/search/all/v2（非 wbi，先建 buvid3 会话）
//   episodes: PGC /pgc/view/web/ep/list ；UGC /x/player/pagelist
//   comments: /x/v2/dm/web/seg.so（protobuf，native 解码，逐段累加）

var _buvid = null;

function _ensureBuvid() {
  if (_buvid !== null) return _buvid;
  try {
    host.httpGet("https://www.bilibili.com", null);
    var spi = JSON.parse(host.httpGet("https://api.bilibili.com/x/frontend/finger/spi", null));
    var d = spi.data || {};
    _buvid = "buvid3=" + (d.b_3 || "") + "; buvid4=" + (d.b_4 || "");
  } catch (e) {
    _buvid = "";
  }
  return _buvid;
}

function _headers() {
  var c = _ensureBuvid();
  return c ? JSON.stringify({ "Cookie": c }) : null;
}

function _clean(t) {
  return (t || "").replace(/<[^>]+>/g, "").replace(/&amp;/g, "&");
}

function search(keyword) {
  var out = [];
  try {
    var url = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" +
      encodeURIComponent(keyword) + "&platform=pc&duration=0&order=totalrank";
    var root = JSON.parse(host.httpGet(url, _headers()));
    var groups = (root.data && root.data.result) || [];
    var pgc = [], ugc = [];
    for (var i = 0; i < groups.length; i++) {
      var g = groups[i], type = g.result_type, items = g.data || [];
      if (type === "media_bangumi" || type === "media_ft") {
        for (var j = 0; j < items.length; j++) {
          var o = items[j];
          if (o.season_id) {
            pgc.push({ provider: "bilibili", mediaId: "ss" + o.season_id, title: _clean(o.title),
              type: (type === "media_ft" ? "movie" : "tvseries"), episodeCount: o.ep_size || null });
          }
        }
      } else if (type === "video") {
        for (var j = 0; j < items.length; j++) {
          var o = items[j];
          if (o.bvid) {
            ugc.push({ provider: "bilibili", mediaId: "bv:" + o.bvid, title: _clean(o.title), type: "video" });
          }
        }
      }
    }
    out = pgc.concat(ugc);   // PGC 优先，UGC 兜底
  } catch (e) {
    host.log("search err: " + e);
  }
  host.log("search '" + keyword + "' -> " + out.length);
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var out = [];
  try {
    if (mediaId.indexOf("ss") === 0) {
      var seasonId = mediaId.substring(2);
      var root = JSON.parse(host.httpGet(
        "https://api.bilibili.com/pgc/view/web/ep/list?season_id=" + seasonId, _headers()));
      var eps = (root.result && root.result.episodes) || [];
      for (var i = 0; i < eps.length; i++) {
        var o = eps[i];
        if (o.cid) {
          out.push({ provider: "bilibili", episodeId: String(o.cid),
            title: (o.long_title || o.title || ("第" + (i + 1) + "集")), episodeIndex: i + 1 });
        }
      }
    } else if (mediaId.indexOf("bv:") === 0) {
      var bvid = mediaId.substring(3);
      var root = JSON.parse(host.httpGet(
        "https://api.bilibili.com/x/player/pagelist?bvid=" + bvid, _headers()));
      var pages = root.data || [];
      for (var i = 0; i < pages.length; i++) {
        var o = pages[i];
        if (o.cid) {
          out.push({ provider: "bilibili", episodeId: String(o.cid),
            title: (o.part || ("P" + (i + 1))), episodeIndex: i + 1 });
        }
      }
    }
  } catch (e) {
    host.log("getEpisodes err: " + e);
  }
  host.log("getEpisodes " + mediaId + " -> " + out.length);
  return JSON.stringify(out);
}

function getComments(episodeId) {
  var all = [];
  try {
    var seg = 1;
    while (seg <= 100) {   // 每段约 6 分钟，空段终止
      var url = "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=" +
        episodeId + "&segment_index=" + seg;
      var part = JSON.parse(host.fetchDanmakuSeg(url, _headers()));
      if (!part || part.length === 0) break;
      for (var i = 0; i < part.length; i++) all.push(part[i]);
      seg++;
    }
    if (all.length === 0) {   // seg.so 为空 → 退回旧版 XML
      var xml = JSON.parse(host.fetchDanmakuXml(
        "https://api.bilibili.com/x/v1/dm/list.so?oid=" + episodeId, _headers()));
      for (var k = 0; k < xml.length; k++) all.push(xml[k]);
    }
  } catch (e) {
    host.log("getComments err: " + e);
  }
  host.log("getComments cid=" + episodeId + " -> " + all.length);
  return JSON.stringify(all);
}
