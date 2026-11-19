"""Export a matched comparison of two full-resolution encoded sRGB renders."""

from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageCms, ImageDraw


reference_path, native_path, output = map(Path, sys.argv[1:])
reference = np.load(reference_path, mmap_mode='r', allow_pickle=False)
assert native_path.stat().st_size == reference.nbytes and reference.dtype == '<f8'
native = np.memmap(native_path, dtype='<f8', mode='r', shape=reference.shape)
board = Image.new('RGB', (1216, 710), '#181a1c')
draw = ImageDraw.Draw(board)
encoded = np.empty(reference.shape, dtype=np.uint8)
different = 0
for i, (image, label) in enumerate(((native, 'FilmKit Spektrafilm (C++)'), (reference, 'Spektrafilm upstream'))):
    for y in range(0, len(image), 32):
        sl = slice(y, y+32)
        encoded[sl] = np.rint(np.clip(image[sl], 0, 1)*255).astype(np.uint8)
        if i == 0:
            different += np.count_nonzero(encoded[sl] != np.rint(np.clip(reference[sl], 0, 1)*255))
    preview = Image.fromarray(encoded)
    preview.thumbnail((580, 660), Image.Resampling.LANCZOS)
    x = i*608
    draw.text((x+12, 10), label, fill='white', font_size=21)
    board.paste(preview, (x+(608-preview.width)//2, 38))
icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
with output.open('xb') as file:
    board.save(file, format='JPEG', quality=95, subsampling=0, icc_profile=icc)
print(f'8-bit RGB components differing: {different}/{reference.size}; {output}')
