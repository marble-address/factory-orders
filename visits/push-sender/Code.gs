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

function sendPending() {
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
      var targets = to === 'ADMINS' ? users.filter(function (u) { return u.role === 'admin'; }) : users.filter(function (u) { return u.id === to; });
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

function loadUsers_() {
  var out = [], token = '';
  do {
    var r = api_('get', FS + '/users?pageSize=300' + (token ? '&pageToken=' + token : ''));
    (r.documents || []).forEach(function (d) {
      var f = d.fields || {};
      var toks = ((f.fcmTokens && f.fcmTokens.arrayValue && f.fcmTokens.arrayValue.values) || []).map(function (v) { return v.stringValue; });
      out.push({ id: d.name.split('/').pop(), role: val_(f.role), tokens: toks });
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
