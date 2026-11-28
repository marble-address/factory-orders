/* إشعارات «قسم المشاريع» — بيبعت إشعارات الموبايل من طابور notifs في Firestore (مشروع marble-visits)
   - التطبيق بيكتب الإشعار في notifs (pushed=false) وبينادي الرابط ده → الإرسال فوري
   - ومشغّل كل 5 دقايق كاحتياطي لو النداء ما وصلش */
var PROJECT = 'marble-visits';
var APP_URL = 'https://marble-address.github.io/factory-orders/visits/';
var ICON = 'https://marble-address.github.io/factory-orders/visits/icon-192.png';
var FS = 'https://firestore.googleapis.com/v1/projects/' + PROJECT + '/databases/(default)/documents';

function doGet() {
  var n = 0;
  try { n = sendPending(); } catch (e) { console.log(e); }
  return ContentService.createTextOutput('ok ' + n);
}

function sendPendingFs_() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) return 0;
  try {
    var q = api_('post', FS + ':runQuery', { structuredQuery: {
      from: [{ collectionId: 'notifs' }],
      where: { fieldFilter: { field: { fieldPath: 'pushed' }, op: 'EQUAL', value: { booleanValue: false } } },
      limit: 50 } });
    var docs = (q || []).filter(function (r) { return r.document; }).map(function (r) { return r.document; });
    if (!docs.length) return 0;
    var users = null, sent = 0;
    docs.forEach(function (d) {
      var f = d.fields || {}, to = val_(f.toUid), id = d.name.split('/').pop();
      if (!users) users = loadUsers_();
      var nsec = val_(f.section) || 'rp';   // «ADMINS» = المديرين العامين + مدير القسم بتاع الإشعار بس
      var targets = to === 'ADMINS' ? users.filter(function (u) { return u.role === 'admin' && (!u.section || u.section === 'all' || u.section === nsec); }) : users.filter(function (u) { return u.id === to; });
      var link = APP_URL + (val_(f.customerId) ? '?c=' + encodeURIComponent(val_(f.customerId)) : '');
      targets.forEach(function (u) {
        (u.tokens || []).forEach(function (tok) {
          var c = fcm_(tok, val_(f.title) || 'قسم المشاريع', val_(f.body) || '', link, id);
          if (c < 300) sent++;
          else if (c === 404 || c === 400) dropToken_(u.id, tok);
        });
      });
      api_('patch', FS + '/notifs/' + id + '?updateMask.fieldPaths=pushed&updateMask.fieldPaths=pushedAt',
        { fields: { pushed: { booleanValue: true }, pushedAt: { integerValue: String(Date.now()) } } });
    });
    return sent;
  } finally { lock.releaseLock(); }
}


/* ===== Supabase (من 28 نوفمبر): البيانات هناك، وFirestore فاضل احتياطي لحد التحويل الكامل =====
   كلمة السر في خصائص السكربت: SB_SECRET (نفس قيمة app_secrets k='push' في Supabase) */
var SB_URL = 'https://lyupqkvueqyybgdqmqdj.supabase.co';
var SB_KEY = 'sb_publishable_hSp0tj9Xbfc4n71RQNQv_A__aOWfT5l';
function sbSecret_() { return PropertiesService.getScriptProperties().getProperty('SB_SECRET'); }
function sb_(fn, body) {
  body = body || {}; body.p_secret = sbSecret_();
  var r = UrlFetchApp.fetch(SB_URL + '/rest/v1/rpc/' + fn, { method: 'post', contentType: 'application/json', muteHttpExceptions: true,
    headers: { apikey: SB_KEY, Authorization: 'Bearer ' + SB_KEY }, payload: JSON.stringify(body) });
  var t = r.getContentText();
  if (r.getResponseCode() >= 300) throw new Error('sb ' + fn + ' → ' + r.getResponseCode() + ' ' + t.slice(0, 300));
  return t ? JSON.parse(t) : null;
}

function sendPending() {
  var n = 0;
  try { n += sendPendingSb_(); } catch (e) { console.log(e); }
  try { n += sendPendingFs_(); } catch (e) { console.log('fs ' + e); }
  return n;
}
function sendPendingSb_() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) return 0;
  try {
    var sent = 0, list = sb_('push_pending', {}) || [];
    list.forEach(function (x) {
      var link = APP_URL + (x.customerId ? '?c=' + encodeURIComponent(x.customerId) : '');
      (x.tokens || []).forEach(function (tok) {
        var c = fcm_(tok, x.title || 'قسم المشاريع', x.body || '', link, x.id);
        if (c < 300) sent++;
        else if (c === 404 || c === 400) { try { sb_('push_drop_token', { p_token: tok }); } catch (e) { console.log(e); } }
      });
    });
    return sent;
  } finally { lock.releaseLock(); }
}

function loadUsers_() {
  var out = [], token = '';
  do {
    var r = api_('get', FS + '/users?pageSize=300' + (token ? '&pageToken=' + token : ''));
    (r.documents || []).forEach(function (d) {
      var f = d.fields || {};
      var toks = ((f.fcmTokens && f.fcmTokens.arrayValue && f.fcmTokens.arrayValue.values) || []).map(function (v) { return v.stringValue; });
      out.push({ id: d.name.split('/').pop(), role: val_(f.role), section: val_(f.section), tokens: toks, name: val_(f.name) || '',
        active: f.active ? f.active.booleanValue : true, attendance: f.attendance ? f.attendance.booleanValue : true, test: !!(f.test && f.test.booleanValue), seen: !!f.lastSeen });
    });
    token = r.nextPageToken || '';
  } while (token);
  return out;
}

function fcm_(token, title, body, link, tag) {
  var res = UrlFetchApp.fetch('https://fcm.googleapis.com/v1/projects/' + PROJECT + '/messages:send', {
    method: 'post', contentType: 'application/json', muteHttpExceptions: true,
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken(), 'x-goog-user-project': PROJECT },
    payload: JSON.stringify({ message: { token: token, data: { link: link },
      notification: { title: title, body: body },
      webpush: { notification: { icon: ICON, badge: ICON, dir: 'rtl', lang: 'ar', tag: tag, renotify: true }, fcm_options: { link: link } } } })
  });
  var c = res.getResponseCode();
  if (c >= 300) console.log('fcm ' + c + ' ' + res.getContentText().slice(0, 300));
  if (c === 400 && !/UNREGISTERED|not a valid FCM registration token|registration-token-not-registered/.test(res.getContentText())) return 500;
  return c;
}

function dropToken_(uid, tok) {
  try {
    api_('post', FS + ':commit', { writes: [{ transform: { document: 'projects/' + PROJECT + '/databases/(default)/documents/users/' + uid,
      fieldTransforms: [{ fieldPath: 'fcmTokens', removeAllFromArray: { values: [{ stringValue: tok }] } }] } }] });
  } catch (e) { console.log(e); }
}

function api_(method, url, body) {
  var o = { method: method, muteHttpExceptions: true, contentType: 'application/json',
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken(), 'x-goog-user-project': PROJECT } };
  if (body) o.payload = JSON.stringify(body);
  var r = UrlFetchApp.fetch(url, o), t = r.getContentText();
  if (r.getResponseCode() >= 300) throw new Error(method + ' ' + url.split('/documents')[1] + ' → ' + r.getResponseCode() + ' ' + t.slice(0, 300));
  return t ? JSON.parse(t) : {};
}
function val_(v) { if (!v) return ''; return v.stringValue != null ? v.stringValue : v.integerValue != null ? v.integerValue : v.booleanValue != null ? v.booleanValue : ''; }

/* شغّلها مرة واحدة: بتعمل المشغّل الاحتياطي كل 5 دقايق */
function setup() {
  ScriptApp.getProjectTriggers().forEach(function (t) { if (t.getHandlerFunction() === 'sendPending') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('sendPending').timeBased().everyMinutes(5).create();
  return 'تمام: ' + sendPending();
}

/* ===== تذكيرات بصمة الحضور =====
   - 8 الصبح: كل مندوب (مفعّل وبصمته شغالة) لسه ما بصمش → إشعار «متنساش البصمة»
   - 9 الصبح: المديرين → مين لسه ما بصمش
   - الجمعة إجازة: مفيش تذكيرات
   المشغّل بيشتغل كل ربع ساعة والدالة بتقرر حسب الساعة (توقيت الرياض)، ومش بتبعت مرتين في نفس اليوم */
function attendanceReminders() {
  var tz = 'Asia/Riyadh', now = new Date();
  var hour = Number(Utilities.formatDate(now, tz, 'H'));
  var dow = Number(Utilities.formatDate(now, tz, 'u'));   // 1=الاتنين … 5=الجمعة … 7=الحد
  var day = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
  if (dow === 5) return 'جمعة';
  var props = PropertiesService.getScriptProperties();
  var out = [];
  if (hour >= 8 && hour < 12 && props.getProperty('att8') !== day) {
    var miss = missingReps_(day);
    miss.forEach(function (u) {
      addNotif_(u.id, 'صباح الخير يا ' + u.name.split(' ')[0] + ' 👋', 'متنساش تعمل بصمة الحضور: افتح التطبيق ودوس «بصمة»', (u.section && u.section !== 'all') ? u.section : 'rp');
    });
    props.setProperty('att8', day); out.push('8: ' + miss.length);
  }
  if (hour >= 9 && hour < 12 && props.getProperty('att9') !== day) {
    var miss9 = missingReps_(day), bySec = {};
    miss9.forEach(function (u) { var s = (u.section && u.section !== 'all') ? u.section : 'rp'; (bySec[s] = bySec[s] || []).push(u); });
    Object.keys(bySec).forEach(function (s) {   // إشعار لكل قسم لوحده (مدير القسم يشوف مناديبه بس)
      addNotif_('ADMINS', 'لسه ما بصمش: ' + bySec[s].length + ' مندوب', bySec[s].map(function (u) { return u.name; }).join('، '), s);
    });
    props.setProperty('att9', day); out.push('9: ' + miss9.length);
  }
  if (out.length) sendPending();
  return out.join(' | ') || 'مفيش';
}

function missingReps_(day) {
  var users = (sb_('srv_query', { p_col: 'users', p_filters: [] }) || []).filter(function (u) {
    return u.role === 'rep' && u.active !== false && u.attendance !== false && !u.test && u.lastSeen; });   // اللي دخل التطبيق مرة على الأقل بس
  var att = sb_('srv_query', { p_col: 'attendance', p_filters: [['day', '==', day]] }) || [], have = {};
  att.forEach(function (a) { have[a.uid] = true; });
  return users.filter(function (u) { return !have[u.id]; }).map(function (u) { return { id: u.id, name: u.name || '', section: u.section }; });
}

function addNotif_(toUid, title, body, section) {
  sb_('srv_set', { p_col: 'notifs', p_id: null, p_data: {
    toUid: toUid, fromUid: 'SYSTEM', fromName: 'قسم المشاريع', title: title, body: String(body).slice(0, 240), kind: 'att',
    section: section || 'rp', read: false, pushed: false, createdAt: '__SV__' } });
}

/* شغّلها مرة واحدة: مشغّل كل ربع ساعة لتذكيرات البصمة */
function setupAttendance() {
  ScriptApp.getProjectTriggers().forEach(function (t) { if (t.getHandlerFunction() === 'attendanceReminders') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('attendanceReminders').timeBased().everyMinutes(15).create();
  return 'تمام';
}

/* ===== لينك دخول جديد (إعادة تعيين كلمة المرور) =====
   التطبيق بيبعت idToken بتاع المدير + uid الحساب → بنتأكد إنه مدير (ولو مدير قسم: الحساب مندوب في قسمه)
   → كلمة مرور مؤقتة عشوائية + mustSetPw=true → التطبيق يعمل منها لينك يشتغل مرة واحدة */
var API_KEY = 'AIzaSyChKkqG2ZVl5K0OUzcJaLRJxcuBL8wKuKY';
function doPost(e) {
  var out;
  try {
    var b = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    out = b.action === 'reset' ? resetPw_(b) : { ok: false, err: 'unknown action' };
  } catch (err) { out = { ok: false, err: String(err.message || err) }; }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}
function resetPw_(b) {
  if (!b.idToken || !b.uid) return { ok: false, err: 'بيانات ناقصة' };
  var r = UrlFetchApp.fetch('https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=' + API_KEY, {
    method: 'post', contentType: 'application/json', muteHttpExceptions: true, payload: JSON.stringify({ idToken: b.idToken }) });
  if (r.getResponseCode() !== 200) return { ok: false, err: 'سجّل دخول تاني' };
  var caller = JSON.parse(r.getContentText()).users[0].localId;
  var me = getUser_(caller), t = getUser_(b.uid);
  if (!me || me.role !== 'admin' || me.active === false) return { ok: false, err: 'مش مسموح' };
  if (!t) return { ok: false, err: 'الحساب مش موجود' };
  if (me.section && me.section !== 'all' && (t.role !== 'rep' || (t.section || 'rp') !== me.section)) return { ok: false, err: 'الحساب ده مش في قسمك' };
  var pw = genPw_();
  var u = UrlFetchApp.fetch('https://identitytoolkit.googleapis.com/v1/projects/' + PROJECT + '/accounts:update', {
    method: 'post', contentType: 'application/json', muteHttpExceptions: true,
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken(), 'x-goog-user-project': PROJECT },
    payload: JSON.stringify({ localId: b.uid, password: pw }) });
  if (u.getResponseCode() >= 300) return { ok: false, err: 'update ' + u.getResponseCode() + ' ' + u.getContentText().slice(0, 200) };
  sb_('srv_update', { p_col: 'users', p_id: b.uid, p_patch: { mustSetPw: true } });
  return { ok: true, username: t.username, password: pw };
}
function getUser_(uid) {
  try { var u = sb_('srv_get', { p_col: 'users', p_id: uid }); if (!u) return null;
    return { role: u.role || '', section: u.section || '', username: u.username || '', active: u.active !== false };
  } catch (e) { return null; }
}
function genPw_() { var c = 'abcdefghjkmnpqrstuvwxyz23456789', s = ''; for (var i = 0; i < 16; i++) s += c.charAt(Math.floor(Math.random() * c.length)); return s; }

/* ===== جسر مؤقت: مواقع تطبيق الأندرويد القديم (النسخة 2/3) =====
   الخدمة الأصلية في النسخ القديمة بتكتب نقط الموقع في Firestore (track) — كل 5 دقايق بننسخ نقط النهارده لـ Supabase
   (النسخة 4 بتكتب في Supabase على طول، ولما الكل ينزّلها نقدر نوقف المشغّل ده) */
function syncTrack() {
  var day = Utilities.formatDate(new Date(), 'Asia/Riyadh', 'yyyy-MM-dd'), n = 0;
  var q = api_('post', FS + ':runQuery', { structuredQuery: { from: [{ collectionId: 'track' }],
    where: { fieldFilter: { field: { fieldPath: 'day' }, op: 'EQUAL', value: { stringValue: day } } }, limit: 100 } });
  (q || []).forEach(function (r) {
    if (!r.document) return;
    var f = r.document.fields || {}, id = r.document.name.split('/').pop();
    var pts = ((f.points && f.points.arrayValue && f.points.arrayValue.values) || []).map(function (v) {
      var m = (v.mapValue && v.mapValue.fields) || {};
      return { t: Number(val_(m.t)), lat: Number(m.lat && (m.lat.doubleValue != null ? m.lat.doubleValue : m.lat.integerValue)),
        lng: Number(m.lng && (m.lng.doubleValue != null ? m.lng.doubleValue : m.lng.integerValue)), acc: Number(val_(m.acc) || 0), k: val_(m.k) || 'p' };
    }).filter(function (p) { return p.t && isFinite(p.lat) && isFinite(p.lng); });
    if (!pts.length) return;
    n += sb_('srv_track_merge', { p_id: id, p_data: { uid: val_(f.uid), name: val_(f.name), day: val_(f.day), section: val_(f.section) || 'rp', points: pts } }) || 0;
  });
  return 'نقط جديدة: ' + n;
}
/* شغّلها مرة واحدة: مشغّل الجسر كل 5 دقايق */
function setupSyncTrack() {
  ScriptApp.getProjectTriggers().forEach(function (t) { if (t.getHandlerFunction() === 'syncTrack') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('syncTrack').timeBased().everyMinutes(5).create();
  return syncTrack();
}
