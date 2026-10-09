# Regenerates frontend/menu/menu_font.h from DejaVu Sans Mono (the copy matplotlib ships).
# Usage: python tools/make-menu-font.py <DejaVuSansMono.ttf>
import sys
from PIL import Image, ImageDraw, ImageFont

W, H, SIZE = 20, 40, 32
font = ImageFont.truetype(sys.argv[1], SIZE)
ascent, descent = font.getmetrics()
top = (H - (ascent + descent)) // 2
out = ["// Generated from DejaVu Sans Mono (Bitstream Vera / DejaVu license, see menu/FONT-LICENSE.txt)",
       "// by tools/make-menu-font.py: printable ASCII 32..126, %dx%d cells, 8-bit coverage." % (W, H),
       "#pragma once", "#include <cstdint>", "namespace MenuFont {",
       "inline constexpr int Width = %d;" % W, "inline constexpr int Height = %d;" % H,
       "inline constexpr std::uint8_t Glyphs[95][%d] = {" % (W * H)]
for c in range(32, 127):
    image = Image.new("L", (W, H), 0)
    ImageDraw.Draw(image).text((0, top), chr(c), font=font, fill=255)
    out.append("{" + ",".join(str(v) for v in image.getdata()) + "},")
out += ["};", "} // namespace MenuFont", ""]
open("frontend/menu/menu_font.h", "w").write("\n".join(out))
