// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static ApplicationLifecycleMonitor instance;
    public static void registerInstance(ApplicationLifecycleMonitor m) { instance = m; }
    public static ApplicationLifecycleMonitor getInstance() { return instance; }
}
