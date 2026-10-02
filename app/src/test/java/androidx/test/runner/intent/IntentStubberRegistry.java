// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.runner.intent;

public final class IntentStubberRegistry {
    public static boolean isLoaded() { return false; }
    public static IntentStubber getInstance() { return null; }
}
