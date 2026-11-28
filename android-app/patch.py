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
