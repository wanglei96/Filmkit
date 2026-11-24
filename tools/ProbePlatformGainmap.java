import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Gainmap;
import android.graphics.ImageDecoder;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

// adb shell CLASSPATH=probe.jar app_process /system/bin ProbePlatformGainmap image.jpg
public final class ProbePlatformGainmap {
    private static String digest(Bitmap bitmap) {
        CRC32 crc = new CRC32();
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) {
                crc.update(pixel >>> 24);
                crc.update(pixel >>> 16);
                crc.update(pixel >>> 8);
                crc.update(pixel);
            }
        }
        return Long.toHexString(crc.getValue());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected JPEG/R path");
        File file = new File(args[0]);
        Map<String, String> previous = new HashMap<>();
        for (int round = 0; round < 3; round++) {
            for (String mode : new String[]{"factory-full", "factory-half", "decoder-full", "decoder-fit"}) {
                Bitmap bitmap;
                if (mode.startsWith("factory")) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = mode.endsWith("half") ? 2 : 1;
                    bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                } else {
                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        if (mode.endsWith("fit")) {
                            double scale = Math.min(1., 1216. / info.getSize().getWidth());
                            decoder.setTargetSize((int) Math.round(info.getSize().getWidth() * scale),
                                                  (int) Math.round(info.getSize().getHeight() * scale));
                        }
                    });
                }
                if (bitmap == null) throw new IllegalStateException("decode failed: " + mode);
                String state = "size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " config=" + bitmap.getConfig() + " cs=" + bitmap.getColorSpace().getName()
                    + " center=" + Integer.toHexString(bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2))
                    + " baseCrc=" + digest(bitmap) + " gainmap=" + bitmap.hasGainmap();
                if (bitmap.hasGainmap()) {
                    Gainmap gainmap = bitmap.getGainmap();
                    state += " min=" + Arrays.toString(gainmap.getRatioMin())
                        + " max=" + Arrays.toString(gainmap.getRatioMax())
                        + " gamma=" + Arrays.toString(gainmap.getGamma())
                        + " epsilonSdr=" + Arrays.toString(gainmap.getEpsilonSdr())
                        + " epsilonHdr=" + Arrays.toString(gainmap.getEpsilonHdr())
                        + " capacity=" + gainmap.getMinDisplayRatioForHdrTransition()
                        + "," + gainmap.getDisplayRatioForFullHdr()
                        + " direction=" + gainmap.getGainmapDirection()
                        + " mapCrc=" + digest(gainmap.getGainmapContents());
                }
                String old = previous.put(mode, state);
                if (old != null && !old.equals(state))
                    throw new IllegalStateException("repeated decode changed: " + mode);
                System.out.println("DECODE round=" + round + " mode=" + mode + " " + state);
                bitmap.recycle();
            }
        }
        System.out.println("PASS repeated platform base/gainmap decoding");
    }
}
