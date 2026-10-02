# ADR 0004 — APK montado sem o Android Gradle Plugin

**Status:** aceita · 2026-10-02

## Contexto
O app precisava sair como APK instalável, mas o ambiente de build não alcança o Maven do Google (`maven.google.com`/`dl.google.com`): não há Android Gradle Plugin, AndroidX, Jetpack Compose, Room, D8/R8 nem o SDK oficial. O que existe são as ferramentas livres empacotadas pelo Ubuntu (`aapt2`, `dx`, `zipalign`, `apksigner`, `android.jar` da API 23) e o Maven Central.

Além disso, o dono do produto pediu um app guiado só por alternativas, sem digitação e sem o chat de IA no fluxo principal.

## Decisão
- **Telas em código** (Views do framework, sem XML de layout e sem AndroidX): um pequeno kit (`app/ui`) com cartões, chips, steppers, barras e gráficos desenhados em `Canvas`. Cada tela reconstrói sua UI a partir do estado no `user.db`.
- **Lógica fora do Android** no módulo `appcore` (questionário, repositório do `user.db`, casos de uso), testada na JVM com SQLite via JDBC. O app só liga telas a esses casos de uso.
- **Acesso a SQLite comum** (`SqlDatabase`): JDBC na JVM, `SQLiteDatabase` do sistema no Android. O mesmo `KnowledgeReader` carrega o `fitness.db` nas duas plataformas.
- **Pipeline do APK** em `app/build.gradle.kts`:
  Kotlin (bytecode Java 11) → jar único → **ProGuard** (backport para Java 7: lambdas e `invokedynamic` viram classes, métodos estáticos de interface são movidos; "manter tudo" para resolver todas as referências) → **dx** (DEX, minSdk 26) → **aapt2** (manifesto, ícone adaptativo, `assets/fitness.db` gerado dos seeds e `assets/user.sql`) → zip dos DEX → **zipalign** → **apksigner** (v2/v3).
- **Checagem de API do Android 8** (`checkAndroidApi`, roda antes do APK): o código é ligado contra `android.jar` (API 23) + a API do Java 8 extraída do `ct.sym` do JDK (o libcore do Android 8 segue o OpenJDK 8). Qualquer referência do FitKingIA a classe/método inexistente falha o build — foi assim que se descobriu (e corrigiu) um backport que deixava chamadas para métodos movidos.
- **Testes de UI com Robolectric** na JVM; os poucos tipos de `androidx.test:monitor` que o Robolectric usa têm stubs mínimos só no source set de teste.
- Chave de assinatura de depuração versionada (`app/signing/debug.p12`) para que atualizações do APK instalem por cima.

## Consequências
- Build reprodutível só com Maven Central + pacotes do Ubuntu (o CI instala `aapt dalvik-exchange libandroid-23-java zipalign apksigner`).
- APK pequeno (~1,6 MB) e sem permissões além de vibração.
- Sem Compose/Room/AndroidX: menos conveniências (sem previews, sem migrações automáticas do Room — o `user.db` usa `PRAGMA user_version`).
- Ao compilar contra a API 23, propriedades Kotlin de getters adicionados depois (ex.: `LinearLayout.gravity`, `GradientDrawable.cornerRadius`) não existem: use os setters.
- Para publicar em loja, trocar a chave de depuração por uma chave de release guardada fora do repositório.
