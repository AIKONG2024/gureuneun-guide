import sys
from PIL import Image
files = sys.argv[2:]
out = sys.argv[1]
cols = 2
w, h = 841, 605
rows = (len(files) + cols - 1) // cols
sheet = Image.new('RGB', (cols * w + (cols - 1) * 8, rows * h + (rows - 1) * 8), (40, 40, 40))
for i, f in enumerate(files):
    im = Image.open(f).convert('RGB').crop((853, 90, 2535, 1300)).resize((w, h), Image.LANCZOS)
    sheet.paste(im, ((i % cols) * (w + 8), (i // cols) * (h + 8)))
sheet.save(out, quality=88)

