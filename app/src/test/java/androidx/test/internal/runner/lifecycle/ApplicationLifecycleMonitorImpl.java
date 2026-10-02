// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.internal.runner.lifecycle;

import android.app.Application;
import androidx.test.runner.lifecycle.ApplicationLifecycleMonitor;
import androidx.test.runner.lifecycle.ApplicationStage;

public class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    public ApplicationLifecycleMonitorImpl() {}
    public void signalLifecycleChange(Application app, ApplicationStage stage) {}
}
