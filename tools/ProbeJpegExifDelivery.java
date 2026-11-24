import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Gainmap;
import android.os.ParcelFileDescriptor;
import androidx.exifinterface.media.ExifInterface;
import com.filmkit.app.EngineBridge;
import com.filmkit.app.camera.JpegExif;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import kotlin.Unit;

// Run with the release DEX and matching native library on the device.
public final class ProbeJpegExifDelivery {
    private static final int W = 3072, H = 4080;
    private static final String[] TAGS = {
        "DateTime", "DateTimeOriginal", "Orientation", "Make", "Model", "PhotographicSensitivity",
        "ExposureTime", "FNumber", "FocalLength", "FocalLengthIn35mmFilm", "WhiteBalance"
    };
    private static final String[] VALUES = {
        "2026:11:23 19:31:41", "2026:11:23 19:31:41", "1", "vivo", "V2509A", "1260",
        "0.020000", "1.7", "69/10", "23", "0"
    };

    private static void tags(ExifInterface exif) {
        for (int i = 0; i < TAGS.length; i++) exif.setAttribute(TAGS[i], VALUES[i]);
    }

    private static String[] readTags(File jpeg) throws Exception {
        ExifInterface exif = new ExifInterface(jpeg.getPath());
        String[] values = new String[TAGS.length];
        for (int i = 0; i < TAGS.length; i++) {
            values[i] = exif.getAttribute(TAGS[i]);
            if (values[i] == null) throw new AssertionError("Missing tag: " + TAGS[i]);
        }
        if (exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) != W ||
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) != H) {
            throw new AssertionError("Wrong EXIF dimensions");
        }
        return values;
    }

    private static long crc(Bitmap bitmap) {
        CRC32 crc = new CRC32();
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) {
                crc.update(pixel >>> 24); crc.update(pixel >>> 16);
                crc.update(pixel >>> 8); crc.update(pixel);
            }
        }
        return crc.getValue();
    }

    private static void verify(File before, File after) throws Exception {
        if (!Arrays.equals(readTags(before), readTags(after))) throw new AssertionError("EXIF tags differ");
        Bitmap a = BitmapFactory.decodeFile(before.getPath()), b = BitmapFactory.decodeFile(after.getPath());
        if (a == null || b == null || !a.hasGainmap() || !b.hasGainmap()) throw new AssertionError("Lost Ultra HDR");
        Gainmap ga = a.getGainmap(), gb = b.getGainmap();
        if (crc(a) != crc(b) || crc(ga.getGainmapContents()) != crc(gb.getGainmapContents()) ||
            !a.getColorSpace().equals(b.getColorSpace()) ||
            !Arrays.equals(ga.getRatioMin(), gb.getRatioMin()) || !Arrays.equals(ga.getRatioMax(), gb.getRatioMax()) ||
            !Arrays.equals(ga.getGamma(), gb.getGamma()) || !Arrays.equals(ga.getEpsilonSdr(), gb.getEpsilonSdr()) ||
            !Arrays.equals(ga.getEpsilonHdr(), gb.getEpsilonHdr()) ||
            ga.getDisplayRatioForFullHdr() != gb.getDisplayRatioForFullHdr() ||
            ga.getMinDisplayRatioForHdrTransition() != gb.getMinDisplayRatioForHdrTransition()) {
            throw new AssertionError("Base or gainmap changed");
        }
        a.recycle(); b.recycle();
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        System.load(new File(dir, "libfilmengine.so").getAbsolutePath());
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(Files.readAllBytes(new File(args[1]).toPath())));
        byte[] gainmap = Files.readAllBytes(new File(args[2]).toPath());
        EngineBridge engine = EngineBridge.INSTANCE;
        File old = new File(dir, "old.jpg"), updated = new File(dir, "updated.jpg");
        for (int i = 0; i < 17; i++) {
            boolean candidate = (i % 4 == 1 || i % 4 == 2);
            File output = candidate ? updated : old;
            long start = System.nanoTime(), exifNs = 0;
            byte[] bytes;
            if (candidate) {
                byte[] exif = JpegExif.INSTANCE.payload(dir, W, H, e -> { tags(e); return Unit.INSTANCE; });
                exifNs = System.nanoTime() - start;
                bytes = engine.nativeEncodeUltraHdrWithExif(bitmap, gainmap, W / 2, H / 2, 100, exif);
            } else {
                bytes = engine.nativeEncodeUltraHdr(bitmap, gainmap, W / 2, H / 2, 100);
            }
            if (bytes == null) throw new AssertionError("Encode failed");
            Files.write(output.toPath(), bytes);
            if (!candidate) {
                long exifStart = System.nanoTime();
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE)) {
                    ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                    tags(exif);
                    exif.saveAttributes();
                }
                exifNs = System.nanoTime() - exifStart;
            }
            System.out.printf("SAVE i=%d candidate=%s ms=%.3f exifMs=%.3f bytes=%d%n",
                i, candidate, (System.nanoTime() - start) / 1e6, exifNs / 1e6, output.length());
        }
        verify(old, updated);
        System.out.println("PASS EXIF tags, platform JPEG and HDR gainmap");
    }
}
