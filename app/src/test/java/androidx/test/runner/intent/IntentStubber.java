// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.runner.intent;

import android.app.Instrumentation;
import android.content.Intent;

public interface IntentStubber {
    Instrumentation.ActivityResult getActivityResultForIntent(Intent intent);
}
