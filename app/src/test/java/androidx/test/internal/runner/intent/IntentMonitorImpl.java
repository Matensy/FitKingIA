// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.internal.runner.intent;

import android.content.Intent;
import androidx.test.runner.intent.IntentMonitor;

public class IntentMonitorImpl implements IntentMonitor {
    public IntentMonitorImpl() {}
    public void signalIntent(Intent intent) {}
}
