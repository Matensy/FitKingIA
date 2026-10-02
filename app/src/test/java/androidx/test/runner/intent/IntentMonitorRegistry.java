// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.runner.intent;

public final class IntentMonitorRegistry {
    private static IntentMonitor instance;
    public static void registerInstance(IntentMonitor m) { instance = m; }
    public static IntentMonitor getInstance() { return instance; }
}
