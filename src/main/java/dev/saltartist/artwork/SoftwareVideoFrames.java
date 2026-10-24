package dev.saltartist.artwork;

/** CPU-only video frames. No shared GPU texture, host Surface or native callback. */
public final class SoftwareVideoFrames implements AutoCloseable {
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;
    private SoftwareCpuRenderer renderer;
    private ScreenshotVideoFrames fallback;
    private boolean closed;

    public SoftwareVideoFrames(String url) { this(url, 1920, 1080); }
    public SoftwareVideoFrames(String url, int maxWidth, int maxHeight) {
        if (maxWidth < 1 || maxWidth > MAX_WIDTH || maxHeight < 1 || maxHeight > MAX_HEIGHT)
            throw new IllegalArgumentException("Unsupported video bounds");
        try { renderer = new SoftwareCpuRenderer(url, maxWidth, maxHeight); }
        catch (RuntimeException | UnsatisfiedLinkError unavailable) {
            // Older host builds may lack the SW render API. Keep the prior safe path.
            fallback = new ScreenshotVideoFrames(url, maxWidth, maxHeight);
        }
    }
    /** Bytes are borrowed until the next read; makeRaster copies them before publication. */
    public record Frame(int width, int height, byte[] bgra) {}
    public String backend() { return renderer != null ? "cpu-render" : "screenshot"; }
    public synchronized Frame read() {
        if (closed) return null;
        return renderer != null ? renderer.read() : fallback.read();
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (renderer != null) renderer.close();
        if (fallback != null) fallback.close();
    }
}
