// Run with compiled plugin classes and the host archive on the classpath, using
// a disposable output directory. Optional third argument replays captured public
// responses (0.body artist search, 5.body Chinese Eminem page) without profile API.
// Use '-' for live requests; optional fourth argument selects FULL_HD / QHD / UHD.
import dev.saltartist.PluginLog;
import dev.saltartist.am.*;
import dev.saltartist.artwork.*;
import dev.saltartist.settings.MotionResolution;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function4;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ArtistDownloadProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("artist output-directory [captured-responses|-] [FULL_HD|QHD|UHD]");
        String name = args[0];
        Path out = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out);
        System.out.println("runtime=" + System.getProperty("java.home") + " GB2312=" + Charset.isSupported("GB2312") + " Big5=" + Charset.isSupported("Big5"));
        Function1<String, String> pages = null;
        Function4<String, String, PluginLog, PreferredArtistLanguage, AmArtistDetails> profiles = null;
        if (args.length > 2 && !args[2].equals("-")) {
            Path captured = Path.of(args[2]);
            String search = Files.readString(captured.resolve("0.body"));
            String page = Files.readString(captured.resolve("5.body"));
            pages = url -> url.contains("itunes.apple.com")
                ? (url.contains("entity=musicArtist") ? search : "{\"results\":[]}") : page;
            profiles = (id, html, log, lang) -> null;
        }
        PluginLog log = new PluginLog(out.resolve("probe.log"));
        var quality = args.length > 3 ? MotionResolution.valueOf(args[3]) : MotionResolution.FULL_HD;
        var policy = new ArtistLanguagePolicy(PreferredArtistLanguage.CHINESE, true, "US,JP,CN", true);
        var identity = new ArtistIdentityPolicy(true, "", "US,JP,CN");
        var visual = new ArtistVisualPolicy("US,JP,CN", PreferredArtistLanguage.ENGLISH, true);
        var catalog = new AppleMusicCatalog(out.toFile(), log, pages, profiles);
        var resolution = catalog.resolveForDownload(name, policy, identity, visual);
        var artist = resolution.getArtist();
        if (resolution.getStatus() != DownloadResolutionStatus.FOUND || artist == null)
            throw new AssertionError("artist metadata unavailable: " + resolution.getStatus());
        int biographyChars = artist.getDetails() == null || artist.getDetails().getBiography() == null ? 0 : artist.getDetails().getBiography().length();
        System.out.println("artist=" + name + " id=" + artist.getArtistId() + " biographyChars=" + biographyChars + " hero=" + (artist.getHeroTemplate() != null) + " avatar=" + (artist.getAvatarTemplate() != null));
        if (biographyChars == 0 && artist.getHeroTemplate() == null && artist.getAvatarTemplate() == null &&
                artist.getLogoTemplate() == null && artist.getMotion() == null)
            throw new AssertionError("no usable artist content");
        var store = new ArtworkStore(out.toFile(), log, catalog);
        try {
            var batch = store.getBatchDownloads();
            if (!batch.start(List.of(name), item -> store.prefetchArtist(item, policy, identity, visual, quality)))
                throw new AssertionError("batch did not start");
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (batch.getProgress().getRunning() && System.nanoTime() < deadline) Thread.sleep(50);
            var progress = batch.getProgress();
            if (progress.getRunning() || progress.getCompleted() != 1 || progress.getAvailable() != 1 ||
                    progress.getRetry() != 0 || progress.getUnavailable() != 0)
                throw new AssertionError("cached artist was not counted as successful: " + progress);
            long imageCount;
            long imageBytes;
            try (var files = Files.walk(out.resolve("apple-music"))) {
                var images = files.filter(Files::isRegularFile).filter(file ->
                    file.toString().endsWith(".jpg") || file.toString().endsWith(".png")).toList();
                imageCount = images.size();
                imageBytes = 0;
                for (Path file : images) imageBytes += Files.size(file);
            }
            if (imageCount == 0 || imageBytes == 0) throw new AssertionError("no images downloaded");
            System.out.println("download=COMPLETE available=" + progress.getAvailable() + " retry=" + progress.getRetry() +
                " refreshPending=" + !catalog.cachedIsFresh(name, policy, identity, visual) +
                " images=" + imageCount + " bytes=" + imageBytes);
        } finally { store.shutdown(); }
        var reloaded = new AppleMusicCatalog(out.toFile(), log, url -> { throw new AssertionError("cache should be offline"); }, profiles);
        var cached = reloaded.cached(name, policy, identity, visual);
        if (cached == null || (biographyChars > 0 && (cached.getDetails() == null || cached.getDetails().getBiography() == null)))
            throw new AssertionError("metadata cache failed to reload");
        System.out.println("offlineReload=PASS id=" + cached.getArtistId());
        if (artist.getMotion() != null) {
            try (var cache = new MotionVideoCache(out.toFile(), log,
                    url -> { throw new AssertionError("cached video must not request a manifest"); },
                    (url, destination) -> { throw new AssertionError("cached video must not download"); })) {
                var source = cache.prepare(artist.getMotion().getUrl(), quality);
                if (!source.getCached() || !Files.isRegularFile(Path.of(source.getLocation())))
                    throw new AssertionError("motion video was not saved for " + quality);
                var reused = cache.downloadForOffline(artist.getMotion().getUrl(), quality).get(3, TimeUnit.SECONDS);
                if (reused == null || !reused.toPath().toAbsolutePath().equals(Path.of(source.getLocation()).toAbsolutePath()))
                    throw new AssertionError("offline download did not reuse the cached movie");
                System.out.println("offlineMotion=PASS quality=" + quality + " bytes=" + Files.size(Path.of(source.getLocation())) +
                    " path=" + source.getLocation());
            }
        }
    }
}
