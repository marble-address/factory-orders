/* Service worker — إشعارات «قسم المشاريع» */
importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-messaging-compat.js');
firebase.initializeApp({ apiKey: 'AIzaSyChKkqG2ZVl5K0OUzcJaLRJxcuBL8wKuKY', authDomain: 'marble-visits.firebaseapp.com', projectId: 'marble-visits',
  storageBucket: 'marble-visits.firebasestorage.app', messagingSenderId: '207433167105', appId: '1:207433167105:web:a7bf86ff145974c22e6b00' });
firebase.messaging();
// الضغط على الإشعار بيفتح الرابط اللي في webpush.fcm_options.link (Firebase بيتعامل معاه)
