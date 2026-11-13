
self.addEventListener('notificationclick', function (e) {
  var d = (e.notification && e.notification.data) || {}, m = d.FCM_MSG || d, w = (m.data && m.data.wid) || '';
  if (!w) return;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
    list.forEach(function (c) { try { c.postMessage({ maOpen: w }); } catch (x) {} });
  }));
});
importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-messaging-compat.js');
firebase.initializeApp({ apiKey: 'AIzaSyDx3sNMVlyFrqDurwOEQEPxpadc-JmUA_Q', authDomain: 'marble-factory-orders.firebaseapp.com', projectId: 'marble-factory-orders',
  storageBucket: 'marble-factory-orders.firebasestorage.app', messagingSenderId: '924003405893', appId: '1:924003405893:web:264813354c6903aab5132c' });
firebase.messaging();
