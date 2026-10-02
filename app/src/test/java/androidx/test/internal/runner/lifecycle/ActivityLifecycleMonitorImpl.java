// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.internal.runner.lifecycle;

import android.app.Activity;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;

public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    public ActivityLifecycleMonitorImpl() {}
    public void signalLifecycleChange(Stage stage, Activity activity) {}
}
