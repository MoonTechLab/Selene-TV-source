// 韩剧TV (hanjutv) 弹幕源。ES5 移植自已 curl 真打验证的草稿（出口东京实测，常量/字段经真实响应核对）。
//
// 两套后端，弹幕互补，建议两路并取合并：
//   A. HXQ「韩小圈」 host=hxqapi.hiyun.tv（备 hxqapi.zmdcq.com）
//      搜索 /api/search/s5（响应【加密】，需 uid + warmup）；详情 /api/series/detail（明文 playItems[].pid）；
//      弹幕 /api/danmu/playItem/list（明文，浏览器 UA，按 pid）。
//   B. TV「极速版/ZTV」 host=api.xiawen.tv
//      搜索 /api/v1/aggregate/search（加密）；详情 /api/v1/series/detail/query（加密 episodes[].pid）；
//      弹幕 /api/v1/bulletchat/episode/get（加密，eid=上面的 pid）。
//
// 加密（仅加密响应需要）：
//   请求头派生：di/uk = AES_CBC(uid, UK_KEY, UK_IV) b64；sign/rp = AES_CBC(jsonPayload, md5(uid)[:16], md5(uid)[16:32]) b64
//   响应解密：key = md5(uid + String(resp.ts))；mix = md5(key + RESPONSE_SECRET)；
//             明文 = AES_CBC_NoPad(b64(resp.data), mix[:16], mix[16:32]) → strip 控制字符 → JSON.parse
//   md5() 取小写 hex 后切片当 16 字节 ASCII key/iv。
// 契约：mode 1滚/5顶；color 十进制 RGB。
var HJ_UK_KEY = "f349wghhe784tqwh";
var HJ_UK_IV = "d3w8hf94fidk38lk";
var HJ_RESPONSE_SECRET = "34F9Q53w/HJW8E6Q";
var HJ_SAID = "fb3597b87601d5a7"; // TV 端固定 ai/said
var HJ_PROVIDER = "hanjutv";
var HXQ_HOST = "https://hxqapi.hiyun.tv";
var HXQ_FALLBACK = "https://hxqapi.zmdcq.com";
var TV_HOST = "https://api.xiawen.tv";
var HJ_MAX_AXIS = 100000000;
var HJ_WINDOW_MS = 60000;
var HJ_CATE = { 1: "韩剧", 2: "综艺", 3: "电影", 4: "日剧", 5: "美剧", 6: "泰剧", 7: "国产剧" };

function _hjRand(chars, n) {
  var s = "";
  for (var i = 0; i < n; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
  return s;
}
function _hjUid() { return _hjRand("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 20); }
function _hjHex(n) { return _hjRand("0123456789abcdef", n); }
function _hjStripCtl(t) { return String(t).replace(/[\u0000-\u001f\u007f-\u009f]/g, ""); }
function _hjCate(c) { return HJ_CATE[c] || "其他"; }
function _hjYear(ms) {
  var n = Number(ms);
  if (!isFinite(n) || n <= 0) return 0;
  var y = new Date(n).getFullYear();
  return (isFinite(y) && y > 1900) ? y : 0;
}
function _hjJson(url, headers) { return JSON.parse(host.httpGet(url, JSON.stringify(headers))); }

// 加密响应 → 明文对象（data + uid + ts）
function _hjDecode(payload, uid) {
  if (!payload || typeof payload !== "object") return payload;
  if (typeof payload.data !== "string" || payload.data.length === 0) return payload;
  var ts = payload.ts != null ? String(payload.ts) : "";
  var key = (typeof payload.key === "string" && payload.key) ? payload.key : "";
  if (!key && uid && ts !== "") key = host.md5(uid + ts);
  if (!key) throw new Error("缺少解密 key");
  var mix = host.md5(key + HJ_RESPONSE_SECRET);
  var plain = host.aesCbcDecryptNoPad(payload.data, mix.slice(0, 16), mix.slice(16, 32));
  return JSON.parse(_hjStripCtl(plain).replace(/^\s+|\s+$/g, ""));
}

// ── 请求头 ──
function _hxqAppHeaders() {
  return { vc: "a_8280", vn: "6.8.2", ch: "xiaomi", app: "hj",
    "User-Agent": "HanjuTV/6.8.2 (Redmi Note 12; Android 14; Scale/2.00)", "Accept-Encoding": "gzip" };
}
function _hxqDanmuHeaders() {
  return { "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36" };
}
function _hxqSearchHeaders(uid, initTs) {
  var uidMd5 = host.md5(uid), said = _hjHex(16), oa = _hjHex(16);
  var installTs = Math.max(0, initTs - 14 * 24 * 60 * 60 * 1000);
  var uk = host.aesCbcEncrypt(uid, HJ_UK_KEY, HJ_UK_IV), ts = Date.now();
  var signPayload = JSON.stringify({ emu: 0, ou: 0, it: installTs, iit: installTs, bs: 0, uid: uid, pc: 0, tm: 81,
    d8m: "0,0,0,0,0,0,0,4", md: "Redmi Note 12", maker: "Xiaomi", osv: "14", br: 95, rpc: 0, scc: 2, plc: 6,
    toc: 19, tsc: 10, ts: ts, pa: 1, crec: 0, nw: 2, px: "0", isp: "", ai: said, oa: oa, dpc: 0, dsc: 0,
    qpc: 0, apad: 0, pk: "com.babycloud.hanju" });
  var sign = host.aesCbcEncrypt(signPayload, uidMd5.slice(0, 16), uidMd5.slice(16, 32));
  return { app: "hj", ch: "xiaomi", said: said, uk: uk, vn: "6.8.2", sign: sign,
    "User-Agent": "HanjuTV/6.8.2 (Redmi Note 12; Android 14; Scale/2.00)", vc: "a_8280",
    "Accept-Encoding": "gzip", Connection: "Keep-Alive" };
}
function _tvHeaders(uid, initTs) {
  var uidMd5 = host.md5(uid), oa = _hjHex(16), ts = Date.now();
  var rpPayload = JSON.stringify({ emu: 0, ou: 0, it: initTs, iit: initTs, bs: 0, uid: uid, isp: "", pc: 0, tm: 50,
    d8m: "0,0,0,0,0,0,14,7", md: "23127PN0CC", dn: "", osv: "16", br: 50, rpc: 0, scc: 1, plc: 1, toc: 5, tsc: 7,
    ts: ts, nw: 2, px: "0", ai: HJ_SAID, oa: oa, dpc: 0, dsc: 0, qpc: 0, apad: 0 });
  var di = host.aesCbcEncrypt(uid, HJ_UK_KEY, HJ_UK_IV);
  var rp = host.aesCbcEncrypt(rpPayload, uidMd5.slice(0, 16), uidMd5.slice(16, 32));
  return { version: "a_22570", "version-name": "1.7.2", channel: "xiaomi", "app-type": "ztv",
    "User-Agent": "ZTV/1.7.2 (23127PN0CC; Android 16; Scale/2.00)", said: HJ_SAID, di: di, token: "", uid: "",
    rp: rp, "Accept-Encoding": "gzip", Connection: "Keep-Alive" };
}

function search(keyword) {
  var key = String(keyword || "").replace(/^\s+|\s+$/g, "");
  if (!key) return "[]";
  var q = encodeURIComponent(key), out = [], seen = {};
  // A 路 HXQ s5（加密 + warmup）
  try {
    var uidA = _hjUid(), initA = Date.now(), hA = _hxqSearchHeaders(uidA, initA);
    try { host.httpGet(HXQ_HOST + "/api/common/configs", JSON.stringify(hA)); } catch (e0) {}
    var rawA = _hjJson(HXQ_HOST + "/api/search/s5?k=" + q + "&srefer=search_input&type=0&page=1", _hxqSearchHeaders(uidA, initA));
    var decA = (typeof rawA.data === "string" && rawA.data) ? _hjDecode(rawA, uidA) : rawA;
    var listA = (decA && decA.seriesList) || (decA && decA.seriesData && decA.seriesData.seriesList) || [];
    for (var i = 0; i < listA.length; i++) {
      var a = listA[i], sidA = a.sid || a.seriesId || a.id, nA = a.name || a.title;
      if (!sidA || !nA || seen["hxq:" + sidA]) continue;
      seen["hxq:" + sidA] = 1;
      out.push({ provider: HJ_PROVIDER, mediaId: "hxq:" + sidA, title: String(nA),
        type: _hjCate(a.category), year: _hjYear(a.publishTime || a.updateTime) });
    }
  } catch (e1) { host.log("hanjutv s5 search fail: " + e1); }
  // B 路 TV aggregate（加密）
  try {
    var uidB = _hjUid(), initB = Date.now();
    var rawB = _hjJson(TV_HOST + "/api/v1/aggregate/search?key=" + q + "&scope=101&page=1", _tvHeaders(uidB, initB));
    var decB = _hjDecode(rawB, uidB);
    var listB = (decB && decB.seriesData && decB.seriesData.series) || (decB && decB.seriesList) || [];
    for (var j = 0; j < listB.length; j++) {
      var b = listB[j], sidB = b.sid || b.id, nB = b.name || b.title;
      if (!sidB || !nB || seen["tv:" + sidB]) continue;
      seen["tv:" + sidB] = 1;
      out.push({ provider: HJ_PROVIDER, mediaId: "tv:" + sidB, title: String(nB),
        type: _hjCate(b.category), year: _hjYear(b.publishTime || b.updateTime) });
    }
  } catch (e2) { host.log("hanjutv tv search fail: " + e2); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var id = String(mediaId || "").replace(/^\s+|\s+$/g, ""), out = [];
  if (id.indexOf("hxq:") === 0) {
    var sidH = id.slice(4);
    try {
      var rawH = _hjJson(HXQ_HOST + "/api/series/detail?sid=" + encodeURIComponent(sidH), _hxqAppHeaders());
      var itemsH = (rawH && rawH.playItems && rawH.playItems.length) ? rawH.playItems : [];
      for (var i = 0; i < itemsH.length; i++) {
        var ih = itemsH[i]; if (!ih.pid) continue;
        var sn = Number(ih.serialNo != null ? ih.serialNo : i + 1);
        out.push({ provider: HJ_PROVIDER, episodeId: "hxq:" + ih.pid, title: ih.title || ("第" + sn + "集"),
          episodeIndex: (isFinite(sn) && sn > 0) ? sn : i + 1 });
      }
    } catch (eh) { host.log("hanjutv hxq episodes fail: " + eh); }
  } else if (id.indexOf("tv:") === 0) {
    var sidT = id.slice(3);
    try {
      var uidT = _hjUid(), initT = Date.now();
      var rawT = _hjJson(TV_HOST + "/api/v1/series/detail/query?sid=" + encodeURIComponent(sidT), _tvHeaders(uidT, initT));
      var decT = _hjDecode(rawT, uidT);
      var itemsT = (decT && decT.episodes && decT.episodes.length) ? decT.episodes : [];
      for (var k = 0; k < itemsT.length; k++) {
        var it = itemsT[k], eid = it.pid || it.eid || it.episodeId; if (!eid) continue;
        var snT = Number(it.serialNo != null ? it.serialNo : k + 1);
        out.push({ provider: HJ_PROVIDER, episodeId: "tv:" + eid, title: it.title || ("第" + snT + "集"),
          episodeIndex: (isFinite(snT) && snT > 0) ? snT : k + 1 });
      }
    } catch (et) { host.log("hanjutv tv episodes fail: " + et); }
  }
  out.sort(function (x, y) { return x.episodeIndex - y.episodeIndex; });
  return JSON.stringify(out);
}

function getComments(episodeId) {
  var id = String(episodeId || "").replace(/^\s+|\s+$/g, "");
  if (id.indexOf("tv:") === 0) return JSON.stringify(_hjFetchTv(id.slice(3)));
  if (id.indexOf("hxq:") === 0) return JSON.stringify(_hjFetchHxq(id.slice(4)));
  var r = _hjFetchHxq(id);
  if (r.length === 0) r = _hjFetchTv(id);
  return JSON.stringify(r);
}

// HXQ 明文弹幕：/api/danmu/playItem/list（按 pid 分窗）
function _hjFetchHxq(pid) {
  var hosts = [HXQ_HOST, HXQ_FALLBACK];
  for (var h = 0; h < hosts.length; h++) {
    var got = [];
    try {
      var prevId = 0, fromAxis = 0, toAxis = HJ_WINDOW_MS, page = 0;
      while (fromAxis < HJ_MAX_AXIS && page < 240) {
        var url = hosts[h] + "/api/danmu/playItem/list?pid=" + encodeURIComponent(pid) +
          "&prevId=" + prevId + "&fromAxis=" + fromAxis + "&toAxis=" + toAxis + "&offset=0";
        var r = _hjJson(url, _hxqDanmuHeaders()); page++;
        var ds = (r && r.danmus && r.danmus.length) ? r.danmus : [];
        for (var i = 0; i < ds.length; i++) got.push(_hjMap(ds[i]));
        var more = Number(r.more || 0) === 1;
        var nextAxis = Number(r.nextAxis != null ? r.nextAxis : HJ_MAX_AXIS);
        var lastId = Number(r.lastId != null ? r.lastId : prevId);
        if (!isFinite(nextAxis) || nextAxis <= fromAxis || nextAxis >= HJ_MAX_AXIS) break;
        if (isFinite(lastId) && lastId > prevId) prevId = lastId;
        fromAxis = nextAxis;
        if (more) continue;
        if (ds.length === 0) break;
        toAxis = fromAxis + HJ_WINDOW_MS;
      }
    } catch (e) { host.log("hanjutv hxq danmu fail(" + hosts[h] + "): " + e); }
    if (got.length > 0) return got;
  }
  return [];
}

// TV 加密弹幕：/api/v1/bulletchat/episode/get（按 eid 分窗）
function _hjFetchTv(eid) {
  var out = [];
  try {
    var uid = _hjUid(), init = Date.now(), prevId = 0, fromAxis = 0, page = 0;
    while (fromAxis < HJ_MAX_AXIS && page < 240) {
      var url = TV_HOST + "/api/v1/bulletchat/episode/get?eid=" + encodeURIComponent(eid) +
        "&prevId=" + prevId + "&fromAxis=" + fromAxis + "&toAxis=" + HJ_MAX_AXIS + "&offset=0";
      var r = _hjDecode(_hjJson(url, _tvHeaders(uid, init)), uid); page++;
      var bc = (r && r.bulletchats && r.bulletchats.length) ? r.bulletchats : [];
      for (var i = 0; i < bc.length; i++) out.push(_hjMap(bc[i]));
      var more = Number(r.more || 0) === 1;
      var nextAxis = Number(r.nextAxis != null ? r.nextAxis : HJ_MAX_AXIS);
      var lastId = Number(r.lastId != null ? r.lastId : prevId);
      if (!isFinite(nextAxis) || nextAxis <= fromAxis || nextAxis >= HJ_MAX_AXIS) break;
      if (isFinite(lastId) && lastId > prevId) prevId = lastId;
      fromAxis = nextAxis;
      if (!more) prevId = 0;
      if (bc.length === 0 && !more) break;
    }
  } catch (e) { host.log("hanjutv tv danmu fail: " + e); }
  return out;
}

// 弹幕字段（HXQ 与 TV 一致）：t=毫秒, con=内容, sc=颜色(十进制RGB), tp=模式(1滚/2顶)
function _hjMap(d) {
  var c = Number(d.sc);
  return { timeMs: Number(d.t) || 0, mode: (Number(d.tp) === 2 ? 5 : 1),
    color: (isFinite(c) && c > 0) ? (c >>> 0) : 16777215, content: String(d.con == null ? "" : d.con) };
}
