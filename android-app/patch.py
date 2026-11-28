# بيظبط مشروع الأندرويد بعد «cap add android»: اسم قناة الإشعار + الأيقونات من أيقونة التطبيق
import os
from PIL import Image

R = 'android/app/src/main/res'
sx = os.path.join(R, 'values', 'strings.xml')
s = open(sx, encoding='utf-8').read()
if 'capacitor_background_geolocation_notification_channel_name' not in s:
    s = s.replace('</resources>', '    <string name="capacitor_background_geolocation_notification_channel_name">تسجيل الموقع</string>\n</resources>')
open(sx, 'w', encoding='utf-8').write(s)

src = Image.open('../visits/icon-512.png').convert('RGBA')
for d, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    folder = os.path.join(R, 'mipmap-' + d)
    os.makedirs(folder, exist_ok=True)
    ic = src.resize((px, px), Image.LANCZOS)
    ic.save(os.path.join(folder, 'ic_launcher.png'))
    ic.save(os.path.join(folder, 'ic_launcher_round.png'))
    fg = int(px * 108 / 48)
    canvas = Image.new('RGBA', (fg, fg), (255, 255, 255, 0))
    inner = int(fg * 0.62)
    canvas.paste(src.resize((inner, inner), Image.LANCZOS), ((fg - inner) // 2, (fg - inner) // 2))
    canvas.save(os.path.join(folder, 'ic_launcher_foreground.png'))
print('patched')

# ---- خدمة الموقع الأصلية (Tracker) ----
import shutil, glob
J = 'android/app/src/main/java/com/marbleaddress/visits'
os.makedirs(J, exist_ok=True)
for f in glob.glob('native/*.java'):
    shutil.copy(f, J)

mf = 'android/app/src/main/AndroidManifest.xml'
m = open(mf, encoding='utf-8').read()
perms = ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'FOREGROUND_SERVICE',
         'FOREGROUND_SERVICE_LOCATION', 'POST_NOTIFICATIONS', 'RECEIVE_BOOT_COMPLETED', 'WAKE_LOCK',
         'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'INTERNET']
add = ''.join(f'    <uses-permission android:name="android.permission.{p}" />\n' for p in perms if f'android.permission.{p}"' not in m)
m = m.replace('<application', add + '    <application', 1)
comps = '''        <service android:name=".TrackerService" android:exported="false" android:foregroundServiceType="location" android:stopWithTask="false" />
        <receiver android:name=".TrackerReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="com.marbleaddress.visits.RESTART" />
            </intent-filter>
        </receiver>
'''
m = m.replace('</application>', comps + '    </application>', 1)
open(mf, 'w', encoding='utf-8').write(m)

bg = 'android/app/build.gradle'
g = open(bg, encoding='utf-8').read()
if 'play-services-location' not in g:
    g += '\ndependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"\n    implementation "androidx.work:work-runtime:2.10.0"\n}\n'
open(bg, 'w', encoding='utf-8').write(g)
print('tracker patched')
