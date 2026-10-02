// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

public final class InstrumentationRegistry {
    private static Instrumentation instrumentation;
    private static Bundle arguments;

    public static void registerInstance(Instrumentation i, Bundle args) { instrumentation = i; arguments = args; }
    public static Instrumentation getInstrumentation() { return instrumentation; }
    public static Bundle getArguments() { return arguments; }
}
