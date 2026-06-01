// ezdmw（通用/聚合源）弹幕。端点来自 misaka .so strings 提取，**未抓包验证**（best-effort）。
// 拓扑：
//   搜索   https://www.ezdmw.site/Index/search_some.html?searchText={kw}  → HTML，正则取 /Index/bangumi/{id}.html 卡片
//   分集   https://www.ezdmw.site/Index/bangumi/{id}.html                 → HTML，正则取 circuit_switch1 → /Index/video/{vid}.html
//   弹幕   https://player.ezdmw.com/index/getData.html?video_id={vid}&json=xml&danmu=&sign={sign}&timeAxis=false&getUser=&page=0
//          其中 sign/nk 需先从 player 视频页 HTML 抽出（_extract_sign_and_nk）；响应是 <d p="t,mode,?,color,...">内容</d> XML。
// 契约：mode 1滚/4底/5顶；color 十进制 RGB。
// ⚠️ getComments 的 sign 抽取与 getData 参数组装为 strings 推断，真机首跑需核对；失败时安全返回 []。
var EZ_PROVIDER = "ezdmw";
var EZ_SITE = "https://www.ezdmw.site";
var EZ_PLAYER = "https://player.ezdmw.com";
var EZ_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function _ezHeaders(referer) {
  var h = { "User-Agent": EZ_UA };
  if (referer) h.Referer = referer;
  return h;
}
function _ezAll(re, text) {
  var out = [], m;
  re.lastIndex = 0;
  while ((m = re.exec(text)) !== null) { out.push(m); if (m.index === re.lastIndex) re.lastIndex++; }
  return out;
}
function _ezStripTags(s) { return String(s).replace(/<[^>]*>/g, "").replace(/^\s+|\s+$/g, ""); }

function search(keyword) {
  var key = String(keyword || "").replace(/^\s+|\s+$/g, "");
  if (!key) return "[]";
  var out = [], seen = {};
  try {
    var html = host.httpGet(EZ_SITE + "/Index/search_some.html?searchText=" + encodeURIComponent(key), JSON.stringify(_ezHeaders(EZ_SITE)));
    // <a ... href=".../Index/bangumi/{id}.html" ...> <img src="..."> ... <p>标题</p> ... </a>
    var re = /href="[^"]*?\/Index\/bangumi\/(\d+)\.html"[^>]*>[\s\S]*?(?:<img[^>]*src="([^"]*)"[^>]*>)?[\s\S]*?<p[^>]*>([\s\S]*?)<\/p>/g;
    var ms = _ezAll(re, html);
    for (var i = 0; i < ms.length; i++) {
      var id = ms[i][1], title = _ezStripTags(ms[i][3]);
      if (!id || !title || seen[id]) continue;
      seen[id] = 1;
      out.push({ provider: EZ_PROVIDER, mediaId: id, title: title, type: "", year: 0 });
    }
  } catch (e) { host.log("ezdmw search fail: " + e); }
  return JSON.stringify(out);
}

function getEpisodes(mediaId) {
  var id = String(mediaId || "").replace(/^\s+|\s+$/g, "");
  if (!id) return "[]";
  var out = [];
  try {
    var html = host.httpGet(EZ_SITE + "/Index/bangumi/" + encodeURIComponent(id) + ".html", JSON.stringify(_ezHeaders(EZ_SITE)));
    // <a class="circuit_switch1" ... href=".../Index/video/{vid}.html" ...> <span class="...">第N集</span>
    var re = /<a\s+class="circuit_switch1"[^>]*href="[^"]*?\/Index\/video\/(\d+)\.html"[^>]*>[\s\S]*?<span[^>]*>([\s\S]*?)<\/span>/g;
    var ms = _ezAll(re, html), idx = 0;
    for (var i = 0; i < ms.length; i++) {
      var vid = ms[i][1], label = _ezStripTags(ms[i][2]);
      if (!vid) continue;
      idx++;
      out.push({ provider: EZ_PROVIDER, episodeId: vid, title: label || ("第" + idx + "集"), episodeIndex: idx });
    }
  } catch (e) { host.log("ezdmw episodes fail: " + e); }
  return JSON.stringify(out);
}

// 从 player 视频页抽 sign（与可选 nk）。多正则兜底；抽不到返回 {}。
function _ezSign(vid) {
  try {
    var page = host.httpGet(EZ_PLAYER + "/Index/video/" + encodeURIComponent(vid) + ".html", JSON.stringify(_ezHeaders(EZ_SITE)));
    var sign = "", nk = "";
    var s1 = /["']?sign["']?\s*[:=]\s*["']([0-9a-zA-Z]+)["']/.exec(page);
    if (s1) sign = s1[1];
    var n1 = /["']?nk["']?\s*[:=]\s*["']([0-9a-zA-Z]+)["']/.exec(page);
    if (n1) nk = n1[1];
    return { sign: sign, nk: nk };
  } catch (e) { host.log("ezdmw sign fail: " + e); return { sign: "", nk: "" }; }
}

function getComments(episodeId) {
  var vid = String(episodeId || "").replace(/^\s+|\s+$/g, "");
  if (!vid) return "[]";
  var out = [];
  try {
    var sg = _ezSign(vid);
    var url = EZ_PLAYER + "/index/getData.html?video_id=" + encodeURIComponent(vid) +
      "&json=xml&danmu=" + encodeURIComponent(sg.nk || "") + "&sign=" + encodeURIComponent(sg.sign || "") +
      "&timeAxis=false&getUser=&page=0";
    var xml = host.httpGet(url, JSON.stringify(_ezHeaders(EZ_PLAYER + "/Index/video/" + vid + ".html")));
    // <d p="time,mode,size,color,...">content</d>（bilibili 同款）
    var re = /<d\s+p="([^"]*)"[^>]*>([\s\S]*?)<\/d>/g;
    var ms = _ezAll(re, xml);
    for (var i = 0; i < ms.length; i++) {
      var p = ms[i][1].split(","), content = _ezStripTags(ms[i][2]);
      if (!content) continue;
      var m = parseInt(p[1], 10);
      out.push({ timeMs: Math.round(parseFloat(p[0]) * 1000) || 0,
        mode: (m === 4 ? 4 : (m === 5 ? 5 : 1)),
        color: parseInt(p[3], 10) || 16777215, content: content });
    }
  } catch (e) { host.log("ezdmw comments fail: " + e); }
  return JSON.stringify(out);
}
