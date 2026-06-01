// 人人影视弹幕源（逆向验证）。
// search: api.rrmj.plus（响应 AES-ECB-PKCS7 加密 key=3b744389882a4067；签名 x-ca-sign=hmacBase64）。
// getComments: static-dm.rrmj.plus 明文 JSON（bilibili 风格 p CSV，无需解密/鉴权）。
// ⚠️ 分集 sid 对游客受华为 WAF 限制/需登录 token——有 episodeSid 时弹幕完全可用。
// 契约返回 JSON：mode 1滚/4底/5顶，color 十进制 RGB。
var RR_API = "https://api.rrmj.plus";
var RR_DM = "https://static-dm.rrmj.plus/v1/produce/danmu";
var RR_SIGN_SECRET = "ES513W0B1CsdUrR13Qk5EgDAKPeeKZY"; // HMAC-SHA256 key（base64 输出）
var RR_DEC_KEY = "3b744389882a4067";                   // AES-128-ECB key
var RR_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

function _rrApiGet(path, params) {
  var t = String((new Date()).getTime());
  var keys = []; for (var k in params) { if (params.hasOwnProperty(k)) keys.push(k); } keys.sort();
  var qs = [];
  for (var i = 0; i < keys.length; i++) qs.push(encodeURIComponent(keys[i]) + "=" + encodeURIComponent(params[keys[i]]));
  var query = qs.join("&");
  var canonical = query ? (path + "?" + query) : path;
  var signStr = "GET\naliId:\nct:web_pc\ncv:1.0.0\nt:" + t + "\n" + canonical;
  var sign = host.hmacBase64("sha256", signStr, RR_SIGN_SECRET);
  var headers = JSON.stringify({ "clientType": "web_pc", "clientVersion": "1.0.0", "ct": "web_pc", "cv": "1.0.0",
    "t": t, "aliId": "", "x-ca-sign": sign, "User-Agent": RR_UA, "Referer": "https://m.rrmj.plus/", "Origin": "https://m.rrmj.plus" });
  var resp = host.httpGet(RR_API + path + (query ? ("?" + query) : ""), headers);
  if (resp && (resp.charAt(0) === "{" || resp.charAt(0) === "[")) return JSON.parse(resp); // 报错时偶发明文
  return JSON.parse(host.aesEcbDecrypt(resp, RR_DEC_KEY));
}

function search(keyword) {
  var out = [];
  try {
    var r = _rrApiGet("/m-station/search/drama", { keywords: keyword });
    var list = (r && r.data && r.data.searchDramaList) || [];
    for (var i = 0; i < list.length; i++) {
      var d = list[i];
      out.push({ provider: "renren", mediaId: String(d.id), title: d.title, type: d.classify || "", year: String(d.year || "") });
    }
  } catch (e) { host.log("renren search err: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var out = [], ds = [];
  try { var r = _rrApiGet("/m-station/drama/page", { hsdrOpen: 0, isAgeLimit: 0, dramaId: mediaId }); ds = (r && r.data && (r.data.dramaSeriesList || r.data.episodeList)) || []; } catch (e) {}
  if (!ds.length) { try { var r2 = _rrApiGet("/m-station/drama/secondary", { dramaId: mediaId }); ds = (r2 && r2.data && r2.data.dramaSeriesList) || []; } catch (e2) {} }
  for (var i = 0; i < ds.length; i++) {
    var e = ds[i];
    out.push({ provider: "renren", episodeId: String(e.id), title: e.title || ("第" + (e.episode || (i + 1)) + "集"), episodeIndex: parseInt(e.episode, 10) || (i + 1) });
  }
  return JSON.stringify(out); // 长期空 → episodeSid 需登录 token（见文件头注）
}

function getComments(episodeId) {
  var out = [];
  try {
    var body = host.httpGet(RR_DM + "/EPISODE/" + episodeId, '{"User-Agent":"' + RR_UA + '"}');
    var arr = JSON.parse(body);
    if (!arr || !arr.length || arr.error) return JSON.stringify(out);
    for (var i = 0; i < arr.length; i++) {
      var it = arr[i]; if (!it || !it.p) continue;
      var p = it.p.split(",");
      var m = parseInt(p[1], 10);
      out.push({ timeMs: Math.round(parseFloat(p[0]) * 1000), mode: (m === 4 ? 4 : (m === 5 ? 5 : 1)), color: parseInt(p[3], 10) || 16777215, content: it.d || "" });
    }
  } catch (e) { host.log("renren comments err: " + e); }
  return JSON.stringify(out);
}
