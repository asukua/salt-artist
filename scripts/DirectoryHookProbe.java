import dev.saltartist.hook.HookTargets;
import java.lang.reflect.*;
import java.util.*;

/** Isolated host-locator check: never registers hooks or starts SPW. Run with the local host JAR on the classpath. */
public final class DirectoryHookProbe {
    public static void main(String[] args) throws Exception {
        var targets = Objects.requireNonNull(HookTargets.INSTANCE.forHost(args.length == 0 ? "1.19.2" : args[0])).getDirectory();
        var type = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052A");
        Object locator = type.getDeclaredFields()[0].get(null);
        var method = type.getMethod("\u037F", String.class, String.class, List.class);
        var ui = type.getMethod("\u037F", String.class, String.class, String.class, List.class);
        Objects.requireNonNull(method.invoke(locator, targets.getViewModelClass(), targets.getStateFlowMethod(), List.of()));
        var getter = Class.forName(targets.getViewModelClass(), false, type.getClassLoader()).getMethod(targets.getStateFlowMethod());
        if (getter.getReturnType() != kotlinx.coroutines.flow.StateFlow.class) throw new AssertionError("Wrong state-flow ABI");
        System.out.println("state-flow-getter-signature=PASS");
        Objects.requireNonNull(ui.invoke(locator, targets.getScreenClass(), HookTargets.ARTIST_PAGE_METHOD,
            HookTargets.ARTIST_PAGE_CALLSITE, List.of(targets.getViewModelClass())));
        System.out.println("navigation-unique-UI-call=PASS");
        try {
            ui.invoke(locator, targets.getScreenClass(), HookTargets.ARTIST_PAGE_METHOD, targets.getScreenClass(),
                List.of("androidx.compose.foundation.layout.BoxScope", targets.getReadyStateClass(), "androidx.compose.foundation.layout.PaddingValues"));
            throw new AssertionError("Old ambiguous call unexpectedly accepted; recheck host contract");
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException) || !error.getCause().getMessage().contains("2 calls")) throw error;
            System.out.println("old-ambiguous-call-reproduced=PASS");
        }
        System.out.println("PASS");
    }
}
