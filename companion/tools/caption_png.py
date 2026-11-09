# 여행 영상 하이라이트에 넣을 자막 그림(투명 PNG)을 만든다.
# ffmpeg 에 drawtext 필터가 없을 때 server.js 가 이 스크립트를 쓴다. (Pillow 필요)
# 사용: python3 caption_png.py "첨성대" font.ttc out.png
import sys
from PIL import Image, ImageDraw, ImageFont

text, font_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
W, H = 1280, 720
img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
font = ImageFont.truetype(font_path, 54)
box = draw.textbbox((0, 0), text, font=font)
tw, th = box[2] - box[0], box[3] - box[1]
x, y = 48, H - 110 - th
draw.rounded_rectangle((x - 22, y - 18, x + tw + 22, y + th + 26), radius=18, fill=(0, 0, 0, 120))
draw.text((x, y - box[1]), text, font=font, fill=(255, 255, 255, 255))
img.save(out)
