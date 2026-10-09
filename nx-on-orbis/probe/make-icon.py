import sys
from PIL import Image, ImageDraw, ImageFont
letter = sys.argv[2] if len(sys.argv) > 2 else ""
img = Image.new("RGB", (512, 512), (24, 28, 48))
d = ImageDraw.Draw(img)
d.rectangle([0, 360, 512, 512], fill=(200, 60, 40))
try:
    big = ImageFont.truetype("arialbd.ttf", 110)
    small = ImageFont.truetype("arialbd.ttf", 64)
except OSError:
    big = small = ImageFont.load_default()
d.text((256, 150), "EDEN", font=big, fill=(255, 255, 255), anchor="mm")
d.text((256, 270), "PS4 PROBE", font=small, fill=(180, 190, 220), anchor="mm")
d.text((256, 436), letter, font=big, fill=(255, 255, 255), anchor="mm")
img.save(sys.argv[1])
