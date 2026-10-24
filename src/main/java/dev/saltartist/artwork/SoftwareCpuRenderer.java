package dev.saltartist.artwork;

import com.sun.jna.*;

/**
 * mpv's public SW render API, with a reusable 64-byte-aligned CPU buffer.
 * x64 ABI and lifetime rules: https://github.com/mpv-player/mpv/blob/master/include/mpv/render.h
 * Only nonblocking event polling and mpv_render_* are used after playback starts.
 * The outer owner serializes read/close; no native function can race destruction.
 */
final class SoftwareCpuRenderer implements AutoCloseable {
    private final NativeLibrary library;
    private final Function update, render, waitEvent;
    private final int maxWidth, maxHeight;
    private Pointer handle, context, aligned;
    private Memory parameters, dimensions, format, stride, blocking, pixels;
    private byte[] copy;
    private int width, height, pendingWidth, pendingHeight, rowBytes;
    private boolean closed, pendingFrame;

    SoftwareCpuRenderer(String url, int maxWidth, int maxHeight) {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("Unsupported platform");
        this.maxWidth = maxWidth; this.maxHeight = maxHeight;
        library = NativeLibrary.getInstance("libmpv-2");
        update = library.getFunction("mpv_render_context_update");
        render = library.getFunction("mpv_render_context_render");
        waitEvent = library.getFunction("mpv_wait_event");
        try {
            handle = library.getFunction("mpv_create").invokePointer(new Object[0]);
            if (handle == null) throw new IllegalStateException("mpv_create failed");
            String[][] options = {
                {"config","no"}, {"vo","libmpv"}, {"hwdec","no"}, {"aid","no"},
                {"mute","yes"}, {"volume","0"}, {"loop-file","inf"}, {"force-window","no"},
                {"audio-file-auto","no"}, {"sub-auto","no"}, {"sub-visibility","no"}, {"osd-level","0"},
                {"sws-scaler","bilinear"}, {"sws-fast","yes"}, {"video-timing-offset","0"},
                {"vd-lavc-threads", maxWidth > 1920 ? "4" : "2"}, {"network-timeout","8"},
                {"demuxer-max-bytes","16777216"}, {"demuxer-max-back-bytes","16777216"}, {"msg-level","all=no"}
            };
            Function set = library.getFunction("mpv_set_option_string");
            for (String[] option : options)
                check(set.invokeInt(new Object[]{handle, option[0], option[1]}), "option " + option[0]);
            check(library.getFunction("mpv_initialize").invokeInt(new Object[]{handle}), "initialize");
            try (Memory result = new Memory(8); Memory init = new Memory(32); Memory api = new Memory(3)) {
                result.clear(); init.clear(); api.setString(0, "sw");
                init.setInt(0, 1); init.setPointer(8, api);
                check(library.getFunction("mpv_render_context_create").invokeInt(new Object[]{result, handle, init}), "SW context");
                context = result.getPointer(0);
                if (context == null) throw new IllegalStateException("Missing SW context");
            }
            Function observe = library.getFunction("mpv_observe_property");
            check(observe.invokeInt(new Object[]{handle, 1L, "dwidth", 4}), "width observer");
            check(observe.invokeInt(new Object[]{handle, 2L, "dheight", 4}), "height observer");
            // Size comes from property-change events, never a blocking core call
            // on the render thread. Do not upscale a lower-resolution fallback.
            check(library.getFunction("mpv_command").invokeInt(new Object[]{handle,
                new StringArray(new String[]{"loadfile", url, "replace"}, "UTF-8")}), "loadfile");
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    SoftwareVideoFrames.Frame read() {
        if (closed) return null;
        drainEvents();
        pendingFrame |= (update.invokeLong(new Object[]{context}) & 1L) != 0;
        if (!pendingFrame || pendingWidth == 0 || pendingHeight == 0) return null;
        if (width != pendingWidth || height != pendingHeight) resize(pendingWidth, pendingHeight);
        check(render.invokeInt(new Object[]{context, parameters}), "render");
        pendingFrame = false;
        if (rowBytes == width * 4) aligned.read(0, copy, 0, copy.length);
        else for (int y = 0; y < height; y++) aligned.read((long)y * rowBytes, copy, y * width * 4, width * 4);
        return new SoftwareVideoFrames.Frame(width, height, copy);
    }

    private void drainEvents() {
        // event_id:int, error:int, reply_userdata:uint64, data:void* (x64).
        // Values belong to mpv until the next wait_event, so copy before looping.
        for (int count = 0; count < 128; count++) {
            Pointer event = waitEvent.invokePointer(new Object[]{handle, 0.0});
            int id = event.getInt(0);
            if (id == 0) return;
            if (id != 22) continue; // MPV_EVENT_PROPERTY_CHANGE
            long key = event.getLong(8);
            if (key != 1 && key != 2) continue;
            Pointer property = event.getPointer(16);
            if (property == null || property.getInt(8) != 4) continue; // MPV_FORMAT_INT64
            Pointer data = property.getPointer(16);
            if (data == null) continue;
            long value = data.getLong(0);
            if (value < 1 || value > (key == 1 ? maxWidth : maxHeight))
                throw new IllegalStateException("Frame exceeds CPU buffer limits");
            if (key == 1) pendingWidth = (int)value; else pendingHeight = (int)value;
        }
    }

    private void resize(int w, int h) {
        releaseBuffers();
        width = w; height = h;
        rowBytes = (w * 4 + 63) & ~63;
        long bytes = (long)rowBytes * h;
        if (bytes > 64L * 1024 * 1024) throw new IllegalStateException("Invalid frame size");
        pixels = new Memory(bytes + 63);
        aligned = pixels.share((64 - (Pointer.nativeValue(pixels) & 63)) & 63);
        copy = new byte[w * h * 4];
        dimensions = new Memory(8); dimensions.setInt(0, w); dimensions.setInt(4, h);
        format = new Memory(5); format.setString(0, "bgr0");
        stride = new Memory(8); stride.setLong(0, rowBytes);
        blocking = new Memory(4); blocking.setInt(0, 0);
        parameters = new Memory(6 * 16); parameters.clear();
        param(0, 17, dimensions); param(1, 18, format); param(2, 19, stride);
        param(3, 20, aligned); param(4, 12, blocking);
    }

    private void param(int index, int type, Pointer data) {
        parameters.setInt(index * 16L, type); parameters.setPointer(index * 16L + 8, data);
    }
    private static void check(int status, String operation) {
        if (status < 0) throw new IllegalStateException("mpv " + operation + " failed: " + status);
    }
    private void releaseBuffers() {
        if (parameters != null) { parameters.close(); parameters = null; }
        if (dimensions != null) { dimensions.close(); dimensions = null; }
        if (format != null) { format.close(); format = null; }
        if (stride != null) { stride.close(); stride = null; }
        if (blocking != null) { blocking.close(); blocking = null; }
        if (pixels != null) { pixels.close(); pixels = null; }
        aligned = null; copy = null;
    }
    public void close() {
        if (closed) return;
        closed = true;
        // render.h requires freeing the context before terminating its mpv core.
        try {
            if (context != null) library.getFunction("mpv_render_context_free").invokeVoid(new Object[]{context});
        } finally {
            context = null;
            try { if (handle != null) library.getFunction("mpv_terminate_destroy").invokeVoid(new Object[]{handle}); }
            finally { handle = null; releaseBuffers(); }
        }
    }
}
