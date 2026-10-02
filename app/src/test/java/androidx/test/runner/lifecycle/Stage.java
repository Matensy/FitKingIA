// Stub mínimo de androidx.test:monitor (só existe no Maven do Google, bloqueado neste ambiente).
// Cobre apenas o que o Robolectric chama ao criar Activities nos testes. Nunca vai para o APK.
package androidx.test.runner.lifecycle;

public enum Stage { PRE_ON_CREATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, RESTARTED, DESTROYED }
