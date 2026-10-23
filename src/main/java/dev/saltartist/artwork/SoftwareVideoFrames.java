package dev.saltartist.artwork;

import com.sun.jna.*;
import java.util.HashMap;

/** Copies a CPU-owned screenshot before freeing the mpv result; no GPU handles are shared. */
public final class SoftwareVideoFrames implements AutoCloseable {
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;
    private final Pointer handle;
    private final Function command, free;
    private final NativeLibrary library;
    private boolean closed;
    public SoftwareVideoFrames(String url) {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("Unsupported platform");
        library = NativeLibrary.getInstance("libmpv-2");
        // This is a new C API handle, never MediaMP's differently shaped JNI wrapper.
        handle = library.getFunction("mpv_create").invokePointer(new Object[0]);
        if(handle==null) throw new IllegalStateException("mpv_create failed");
        command = library.getFunction("mpv_command_ret");
        free = library.getFunction("mpv_free_node_contents");
        try {
            String[][] options={{"config","no"},{"vo","null"},{"hwdec","no"},{"aid","no"},
                {"mute","yes"},{"volume","0"},{"loop-file","inf"},{"force-window","no"},
                {"audio-file-auto","no"},{"sub-auto","no"},{"osd-level","0"},
                {"vd-lavc-threads","2"},{"network-timeout","8"},{"demuxer-max-bytes","16777216"},
                {"demuxer-max-back-bytes","16777216"},{"hls-bitrate","8000000"}};
            Function set=library.getFunction("mpv_set_option_string");
            for(String[] option:options) if(set.invokeInt(new Object[]{handle,option[0],option[1]})<0)
                throw new IllegalStateException("mpv rejected "+option[0]);
            if(library.getFunction("mpv_initialize").invokeInt(new Object[]{handle})<0)
                throw new IllegalStateException("mpv_initialize failed");
            if(library.getFunction("mpv_command").invokeInt(new Object[]{handle,new StringArray(new String[]{"loadfile",url,"replace"},"UTF-8")})<0)
                throw new IllegalStateException("loadfile failed");
        } catch(Throwable error) { close(); throw error; }
    }
    public record Frame(int width, int height, byte[] bgra) {}
    public synchronized Frame read() {
        if(closed) return null;
        try (Memory result = new Memory(16)) {
            result.clear();
            StringArray args = new StringArray(new String[]{"screenshot-raw", "video"}, "UTF-8");
            int status = command.invokeInt(new Object[]{handle, args, result});
            if (status < 0) return null;
            try {
                if (result.getInt(8) != 8) throw new IllegalStateException("Expected mpv node map");
                Pointer list = result.getPointer(0);
                int count = list.getInt(0);
                if (count < 0 || count > 32) throw new IllegalStateException("Invalid node count");
                Pointer values = list.getPointer(8), keys = list.getPointer(16);
                var nodes = new HashMap<String, Pointer>();
                for (int i=0; i<count; i++) nodes.put(keys.getPointer(i*8L).getString(0,"UTF-8"), values.share(i*16L));
                int width = integer(nodes.get("w")), height = integer(nodes.get("h")), stride = integer(nodes.get("stride"));
                if (width<1 || height<1 || width>MAX_WIDTH || height>MAX_HEIGHT || stride<width*4 || stride>MAX_WIDTH*8)
                    throw new IllegalStateException("Frame exceeds CPU buffer limits");
                Pointer formatNode = nodes.get("format");
                if (formatNode == null || formatNode.getInt(8)!=1) throw new IllegalStateException("Missing pixel format");
                String format = formatNode.getPointer(0).getString(0,"UTF-8");
                if (!format.equals("bgr0") && !format.equals("bgra") && !format.equals("rgb0") && !format.equals("rgba"))
                    throw new IllegalStateException("Unsupported pixel format: "+format);
                Pointer dataNode = nodes.get("data");
                if (dataNode == null || dataNode.getInt(8)!=9) throw new IllegalStateException("Missing frame bytes");
                Pointer bytes = dataNode.getPointer(0);
                long size = bytes.getLong(8);
                if (size < (long)stride*height || size>16*1024*1024) throw new IllegalStateException("Invalid frame size");
                Pointer pixels = bytes.getPointer(0);
                byte[] copy = new byte[width*height*4];
                for (int y=0; y<height; y++) pixels.read((long)y*stride,copy,y*width*4,width*4);
                for(int i=0;i<copy.length;i+=4) {
                    if (format.startsWith("rgb")) { byte r=copy[i];copy[i]=copy[i+2];copy[i+2]=r; }
                    copy[i+3]=(byte)255;
                }
                return new Frame(width,height,copy);
            } finally { free.invokeVoid(new Object[]{result}); }
        }
    }
    public synchronized void close() {
        if(closed) return;
        closed=true;
        library.getFunction("mpv_terminate_destroy").invokeVoid(new Object[]{handle});
    }
    private static int integer(Pointer value) {
        if (value == null || value.getInt(8)!=4) throw new IllegalStateException("Invalid numeric frame field");
        return Math.toIntExact(value.getLong(0));
    }
}
