import dev.saltartist.artwork.MotionFramePacer;
import dev.saltartist.artwork.SoftwareVideoFrames;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ColorType;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

/**
 * Standalone production CPU-decoder probe; never starts a host window or writes its cache.
 * Args: local-mp4 max-width max-height rounds output-directory.
 * Compile against build/classes/{java,kotlin}/main and the locally installed host archive.
 * Run with the matching host runtime/native library path; logs and PNGs go only to output-directory.
 */
public class MotionStartupProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("local-mp4 max-width max-height rounds output-directory");
        Locale.setDefault(Locale.ROOT);
        Path movie = Path.of(args[0]).toAbsolutePath();
        int maxWidth = Integer.parseInt(args[1]), maxHeight = Integer.parseInt(args[2]);
        int rounds = Integer.parseInt(args[3]);
        Path output = Path.of(args[4]).toAbsolutePath();
        if (!Files.isRegularFile(movie) || rounds < 1) throw new IllegalArgumentException("Missing movie or invalid rounds");
        Files.createDirectories(output);
        long loading = System.nanoTime();
        Method loader = Class.forName("dev.saltartist.artwork.ArtistMotionPlayerKt").getDeclaredMethod("ensureHostMpvLoaded");
        loader.setAccessible(true);
        loader.invoke(null);
        System.out.printf("HOST_NATIVE_LOADER_MS=%.3f%n", (System.nanoTime() - loading) / 1e6);
        Path report = output.resolve("production-results.jsonl");
        Files.writeString(report, "");
        for (int round = 1; round <= rounds; round++) {
            String row = run(movie, maxWidth, maxHeight, round, output);
            System.out.println(row);
            Files.writeString(report, row + "\n", StandardOpenOption.APPEND);
        }
        System.out.println("PRODUCTION_PROBE_COMPLETE");
        System.exit(0);
    }

    private static String run(Path movie, int maxWidth, int maxHeight, int round, Path output) throws Exception {
        long opening = System.nanoTime(), first = 0, firstDecoded = 0, last = 0;
        int frames = 0, width = 0, height = 0, firstVisible = 0, firstHash = 0;
        HashSet<Integer> signatures = new HashSet<>();
        MotionFramePacer pacing = new MotionFramePacer(30);
        Image firstImage = null, currentImage = null;
        SoftwareVideoFrames decoder = new SoftwareVideoFrames(movie.toString(), maxWidth, maxHeight);
        long opened = System.nanoTime();
        try {
            while (first == 0 || System.nanoTime() - first < 3_000_000_000L) {
                long frameStarted = System.nanoTime();
                SoftwareVideoFrames.Frame frame = decoder.read();
                if (frame != null) {
                    long decoded = System.nanoTime();
                    width = frame.width(); height = frame.height();
                    // makeRaster copies the decoder's borrowed BGRA bytes before another read can reuse them.
                    Image image = Image.Companion.makeRaster(new ImageInfo(width, height, ColorType.BGRA_8888,
                        ColorAlphaType.OPAQUE), frame.bgra(), width * 4);
                    long published = System.nanoTime();
                    Image previous = currentImage;
                    currentImage = image;
                    // Retain only the first raster for the PNG; retire every other replaced frame immediately.
                    if (previous != null && previous != firstImage) previous.close();
                    int visible = 0, hash = 1;
                    for (int sample = 0; sample < 4096; sample++) {
                        int offset = (int)((long)sample * (width * height - 1) / 4095) * 4;
                        int rgb = (frame.bgra()[offset] & 255) | ((frame.bgra()[offset + 1] & 255) << 8)
                            | ((frame.bgra()[offset + 2] & 255) << 16);
                        hash = hash * 31 + rgb;
                        if ((rgb & 255) + ((rgb >>> 8) & 255) + ((rgb >>> 16) & 255) > 24) visible++;
                    }
                    signatures.add(hash);
                    if (first == 0) {
                        first = published; firstDecoded = decoded;
                        firstImage = image; firstVisible = visible; firstHash = hash;
                    }
                    frames++; last = published;
                } else if (first == 0 && System.nanoTime() - opening > 15_000_000_000L) {
                    throw new IllegalStateException("No CPU frame within 15 seconds");
                }
                Thread.sleep(frame == null ? 2L : pacing.afterFrame(frameStarted, System.nanoTime()));
            }
            if (signatures.size() < 2) throw new IllegalStateException("Video frames did not change");
            if (firstImage != null) try (Data png = firstImage.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                if (png == null) throw new IllegalStateException("Could not encode first raster");
                Files.write(output.resolve("production-first-" + round + ".png"), png.getBytes());
            }
            return String.format("{\"round\":%d,\"backend\":\"%s\",\"firstMs\":%.3f,\"openMs\":%.3f," +
                "\"waitFirstDecodeMs\":%.3f,\"firstRasterMs\":%.3f,\"frames\":%d,\"distinct\":%d," +
                "\"fps\":%.3f,\"width\":%d,\"height\":%d,\"firstVisiblePixels\":%d,\"firstSampleHash\":%d}",
                round, decoder.backend(), (first - opening) / 1e6, (opened - opening) / 1e6,
                (firstDecoded - opened) / 1e6, (first - firstDecoded) / 1e6, frames, signatures.size(),
                (frames - 1) * 1e9 / (last - first), width, height, firstVisible, firstHash);
        } finally {
            if (currentImage != null && currentImage != firstImage) currentImage.close();
            if (firstImage != null) firstImage.close();
            decoder.close();
        }
    }
}
