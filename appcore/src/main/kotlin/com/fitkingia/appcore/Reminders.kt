package com.fitkingia.appcore

import com.fitkingia.core.model.Fmt
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// =========================================================================================
// Lembretes locais de incentivo (água, treino do dia, sequência).
//
// Tudo aqui é lógica pura e testável na JVM: o app Android só agenda o próximo horário
// (AlarmManager) e, quando ele chega, pergunta a este arquivo se há algo útil para dizer.
// Nada de prescrição: as mensagens só leem o que os motores e o user.db já calcularam
// (meta de água, treino do dia, sequência). Sem servidor e sem internet.
// =========================================================================================

/** Tipos de lembrete, na ordem de prioridade quando caem no mesmo horário. */
enum class ReminderKind(val notificationId: Int, val title: String) {
    WORKOUT(2, "Treino de hoje"),
    WATER(1, "Água"),
    MOTIVATION(3, "Sua sequência"),
}

/**
 * Preferências dos lembretes (chaves `reminder_*` em user_preferences). Padrão: tudo desligado
 * até a pessoa ativar. A janela vale para todos os tipos: nada é enviado fora dela.
 */
data class ReminderSettings(
    val waterEnabled: Boolean = false,
    val waterEveryHours: Int = 2,
    val windowStart: Int = 8,
    val windowEnd: Int = 21,
    val workoutEnabled: Boolean = false,
    val workoutHour: Int = 18,
    val motivationEnabled: Boolean = false,
) {
    val anyEnabled: Boolean get() = waterEnabled || workoutEnabled || motivationEnabled

    /** Dentro da janela (início e fim inclusivos, em horas cheias). */
    fun inWindow(t: LocalTime): Boolean {
        val m = t.hour * 60 + t.minute
        return m >= windowStart * 60 && m <= windowEnd * 60
    }

    /** Horários de treino que cabem na janela atual (a tela só oferece estes). */
    val workoutHoursInWindow: List<Int> get() = WORKOUT_HOURS.filter { it in windowStart..windowEnd }

    /**
     * Valores sempre dentro das opções da tela, e o horário do treino sempre dentro da janela:
     * se a janela mudar e deixar o treino de fora, ele vai para o horário permitido mais próximo
     * (empate → o mais tarde).
     */
    fun normalized(): ReminderSettings {
        val every = waterEveryHours.takeIf { it in WATER_INTERVALS } ?: DEFAULT.waterEveryHours
        val start = windowStart.takeIf { it in WINDOW_STARTS } ?: DEFAULT.windowStart
        val end = windowEnd.takeIf { it in WINDOW_ENDS } ?: DEFAULT.windowEnd
        val inside = WORKOUT_HOURS.filter { it in start..end }
        val hour = if (workoutHour in inside) workoutHour
        else inside.minWithOrNull(compareBy<Int> { Math.abs(it - workoutHour) }.thenByDescending { it }) ?: workoutHour.coerceIn(start, end)
        return copy(waterEveryHours = every, windowStart = start, windowEnd = end, workoutHour = hour)
    }

    fun save(repo: UserRepository): Boolean {
        // user_preferences pertence ao usuário (FK): sem perfil não há onde guardar.
        if (!repo.hasUser()) return false
        val n = normalized()
        repo.setPref(KEY_WATER, n.waterEnabled.toString())
        repo.setPref(KEY_WATER_EVERY, n.waterEveryHours.toString())
        repo.setPref(KEY_WINDOW_START, n.windowStart.toString())
        repo.setPref(KEY_WINDOW_END, n.windowEnd.toString())
        repo.setPref(KEY_WORKOUT, n.workoutEnabled.toString())
        repo.setPref(KEY_WORKOUT_HOUR, n.workoutHour.toString())
        repo.setPref(KEY_MOTIVATION, n.motivationEnabled.toString())
        // Quem já ligou algum lembrete (por Mais › Lembretes) não precisa mais do convite da tela Hoje.
        if (n.anyEnabled && repo.pref(KEY_INVITE) == null) repo.setPref(KEY_INVITE, "configured")
        return true
    }

    companion object {
        val DEFAULT = ReminderSettings()
        val WATER_INTERVALS = listOf(1, 2, 3)
        val WINDOW_STARTS = listOf(6, 7, 8, 9, 10)
        val WINDOW_ENDS = listOf(19, 20, 21, 22)
        val WORKOUT_HOURS = listOf(6, 7, 12, 17, 18, 19, 20)

        const val KEY_WATER = "reminder_water"
        const val KEY_WATER_EVERY = "reminder_water_every_hours"
        const val KEY_WINDOW_START = "reminder_window_start"
        const val KEY_WINDOW_END = "reminder_window_end"
        const val KEY_WORKOUT = "reminder_workout"
        const val KEY_WORKOUT_HOUR = "reminder_workout_hour"
        const val KEY_MOTIVATION = "reminder_motivation"

        /** Resposta ao convite da tela Hoje ("accepted", "dismissed" ou "configured"); sem valor = ainda não perguntado. */
        const val KEY_INVITE = "reminder_invite"

        fun load(repo: UserRepository): ReminderSettings {
            if (!repo.hasUser()) return DEFAULT
            fun int(k: String, d: Int) = repo.pref(k)?.toIntOrNull() ?: d
            return ReminderSettings(
                waterEnabled = repo.pref(KEY_WATER) == "true",
                waterEveryHours = int(KEY_WATER_EVERY, DEFAULT.waterEveryHours),
                windowStart = int(KEY_WINDOW_START, DEFAULT.windowStart),
                windowEnd = int(KEY_WINDOW_END, DEFAULT.windowEnd),
                workoutEnabled = repo.pref(KEY_WORKOUT) == "true",
                workoutHour = int(KEY_WORKOUT_HOUR, DEFAULT.workoutHour),
                motivationEnabled = repo.pref(KEY_MOTIVATION) == "true",
            ).normalized()
        }
    }
}

/** Um horário de disparo e os tipos que vencem nele. */
data class ReminderSlot(val time: LocalDateTime, val kinds: Set<ReminderKind>)

/** Situação do treino de hoje, já lida do programa e dos registros. */
enum class WorkoutDay { PENDING, IN_PROGRESS, DONE, REST, NO_PROGRAM }

/** Retrato do momento do disparo — tudo o que as mensagens precisam, sem acesso a banco. */
data class ReminderContext(
    val now: LocalDateTime,
    val hasProfile: Boolean = true,
    val waterConsumedMl: Int? = null,
    val waterTargetMl: Int? = null,
    val workoutDay: WorkoutDay = WorkoutDay.NO_PROGRAM,
    val sessionTitle: String? = null,
    val sessionMinutes: Int? = null,
    /** Dias seguidos com algum registro, terminando hoje ou ontem (mesma regra do 🔥 da tela Hoje). */
    val streak: Int = 0,
    val activeToday: Boolean = false,
)

/**
 * O que notificar. [kinds] diz quais tipos foram juntados no texto (o app guarda isso na notificação
 * para retirá-la quando o motivo acabar); [coversStreak] = o texto já chama para a sequência.
 */
data class ReminderMessage(
    val kind: ReminderKind,
    val title: String,
    val text: String,
    val notificationId: Int = kind.notificationId,
    val kinds: Set<ReminderKind> = setOf(kind),
    val coversStreak: Boolean = false,
)

/**
 * Quando avisar e o que dizer. Regras de convivência (não são regras de treino):
 *  - nada fora da janela escolhida: vale o horário marcado, com uma pequena tolerância para o alarme
 *    inexato que chega uns minutos depois do último horário (nunca mais que [LATE_GRACE_MINUTES]);
 *  - o alarme é sempre inexato; com o horário longe, passagens ([alarmAt]) encurtam o atraso possível;
 *  - água só se estiver abaixo do ritmo esperado para a hora (meta distribuída por igual na janela);
 *    meta batida = silêncio;
 *  - treino só em dia de treino ainda não feito; no descanso, uma mensagem leve se a motivação estiver ligada
 *    (no horário do treino ou, com o lembrete do treino desligado, no horário da motivação);
 *  - notificação cujo motivo acabou (treino feito, água em dia, outro dia) sai da barra ([stillRelevant]);
 *  - frases variam de forma determinística (dia do ano + hora), sempre sem culpa.
 */
object ReminderPlanner {
    /** Alarme que chegou mais atrasado que isso (aparelho desligado, modo soneca) não vira notificação. */
    const val MAX_DELAY_MINUTES = 90L

    /**
     * Tolerância depois do fim da janela para um horário marcado *dentro* dela (ex.: 21h com janela até
     * 21h) cujo alarme inexato chegou atrasado. Menor que 30 min: 21h30 continua "fora da janela".
     */
    const val LATE_GRACE_MINUTES = 20L

    /**
     * Quanto o Android pode atrasar um alarme inexato (setAndAllowWhileIdle), como fração da antecedência
     * com que ele foi armado: armado às 21h para as 10h do dia seguinte, pode chegar ~10 h depois no
     * Android 8–11 (no 12+ o atraso tem teto de 1 h, ainda maior que [LATE_GRACE_MINUTES]).
     */
    const val INEXACT_WINDOW_FRACTION = 0.75

    /** Com o horário a até esta antecedência, o alarme vai direto nele: atraso de no máximo 15 min (75% de 20). */
    const val DIRECT_LEAD_MINUTES = 20L

    /** Marcos da sequência que merecem comemoração mesmo quando o dia já está garantido. */
    val STREAK_MILESTONES = setOf(3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365)

    /**
     * Para quando armar o alarme (sempre inexato, nunca exato) do aviso de [slot], estando em [now]. Perto do
     * horário (até [DIRECT_LEAD_MINUTES]) arma no próprio horário. Longe dele arma uma *passagem*: um alarme
     * que só reagenda e que, mesmo chegando com o atraso máximo, chega até o horário (4/7 da antecedência;
     * 4/7 × 1,75 = 1). Cada passagem encurta a antecedência e, com ela, o atraso possível do aviso — sem
     * isso, o primeiro aviso do dia (armado na noite anterior) podia chegar horas depois e ser descartado.
     * Do fim da janela até o primeiro aviso do dia seguinte são umas 5 passagens; entre avisos de hora em
     * hora, 2.
     */
    fun alarmAt(now: LocalDateTime, slot: LocalDateTime): LocalDateTime {
        val lead = Duration.between(now, slot)
        if (lead <= Duration.ofMinutes(DIRECT_LEAD_MINUTES)) return slot
        return now.plusSeconds((lead.seconds / (1 + INEXACT_WINDOW_FRACTION)).toLong())
    }

    /** O alarme do aviso de [slot] chegou em [now] antes do horário: é uma passagem (só reagenda, não avisa). */
    fun isRelay(slot: LocalDateTime, now: LocalDateTime): Boolean = now.isBefore(slot)

    /** Horários do dia [date] para cada tipo ligado. */
    fun slots(s: ReminderSettings, date: LocalDate): List<ReminderSlot> {
        val n = s.normalized()
        val byTime = sortedMapOf<LocalTime, MutableSet<ReminderKind>>()
        fun add(hour: Int, kind: ReminderKind) {
            val t = LocalTime.of(hour, 0)
            if (n.inWindow(t)) byTime.getOrPut(t) { linkedSetOf() }.add(kind)
        }
        if (n.waterEnabled) {
            // Primeiro aviso um intervalo depois do início: no começo da janela o ritmo esperado é zero.
            var h = n.windowStart + n.waterEveryHours
            while (h <= n.windowEnd) { add(h, ReminderKind.WATER); h += n.waterEveryHours }
        }
        if (n.workoutEnabled) add(n.workoutHour, ReminderKind.WORKOUT)
        if (n.motivationEnabled) add(n.windowEnd - 1, ReminderKind.MOTIVATION)
        return byTime.map { (t, kinds) -> ReminderSlot(date.atTime(t), kinds.sortedBy { it.ordinal }.toCollection(linkedSetOf())) }
    }

    /** Próximo horário estritamente depois de [after] (hoje ou nos próximos dias); null = tudo desligado. */
    fun next(s: ReminderSettings, after: LocalDateTime): ReminderSlot? {
        if (!s.anyEnabled) return null
        for (d in 0L..2L) {
            slots(s, after.toLocalDate().plusDays(d)).firstOrNull { it.time.isAfter(after) }?.let { return it }
        }
        return null
    }

    /** Tipos que vencem exatamente em [slot]. */
    fun due(s: ReminderSettings, slot: LocalDateTime): Set<ReminderKind> =
        slots(s, slot.toLocalDate()).firstOrNull { it.time == slot }?.kinds.orEmpty()

    /** Mensagem para o disparo de [slot], avaliada em [ctx].now. Null = não notificar. */
    fun messageAt(s: ReminderSettings, slot: LocalDateTime, ctx: ReminderContext): ReminderMessage? {
        if (!ctx.hasProfile) return null
        if (ctx.now.toLocalDate() != slot.toLocalDate()) return null
        // Antes do horário (passagem ou relógio que voltou): nada de aviso adiantado.
        if (isRelay(slot, ctx.now)) return null
        if (Duration.between(slot, ctx.now).toMinutes() > MAX_DELAY_MINUTES) return null
        if (!arrivedInWindow(s, slot, ctx.now)) return null
        return compose(withRestNote(s, due(s, slot), ctx).mapNotNull { message(it, s, ctx) })
    }

    /**
     * O horário marcado já está na janela (due só devolve horários de [slots]); aqui conta a hora em que o
     * alarme chegou: nunca antes do início e, depois do fim, só dentro de [LATE_GRACE_MINUTES]. Comparar a
     * hora de chegada com a janela fazia o último horário (= fim da janela) se perder sempre que o alarme
     * inexato chegava 1 minuto atrasado.
     */
    fun arrivedInWindow(s: ReminderSettings, slot: LocalDateTime, now: LocalDateTime): Boolean {
        val n = s.normalized()
        val day = slot.toLocalDate()
        if (now.isBefore(day.atTime(n.windowStart, 0))) return false
        return !now.isAfter(day.atTime(n.windowEnd, 0).plusMinutes(LATE_GRACE_MINUTES))
    }

    /**
     * A chave da motivação promete "no dia de descanso, uma mensagem leve". Com o lembrete do treino
     * desligado não há horário de treino: a nota de descanso vai junto com o recado da motivação.
     */
    private fun withRestNote(s: ReminderSettings, kinds: Set<ReminderKind>, ctx: ReminderContext): Set<ReminderKind> =
        if (ReminderKind.MOTIVATION in kinds && !s.workoutEnabled && ctx.workoutDay == WorkoutDay.REST) kinds + ReminderKind.WORKOUT else kinds

    /** O tipo está ligado para este dia? (a nota de descanso também vale só com a motivação ligada) */
    fun switchedOn(kind: ReminderKind, s: ReminderSettings, ctx: ReminderContext): Boolean = when (kind) {
        ReminderKind.WATER -> s.waterEnabled
        ReminderKind.WORKOUT -> s.workoutEnabled || (s.motivationEnabled && ctx.workoutDay == WorkoutDay.REST)
        ReminderKind.MOTIVATION -> s.motivationEnabled
    }

    /**
     * Uma notificação postada em [postedOn] com os tipos [kinds] ainda faz sentido em [ctx].now? Falso
     * quando o motivo do tipo principal (o que dá título e id à notificação: treino › água › sequência)
     * acabou — treino feito, água de volta ao ritmo, sequência garantida hoje —, quando ele foi desligado,
     * quando o dia virou ("Hoje tem…" de ontem) ou quando os dados foram apagados. Uma linha secundária
     * resolvida não derruba a notificação: "Hoje tem Treino A" + água continua até o treino ser feito.
     * Com [postedTitle] (o título da notificação na barra), o tipo principal também precisa continuar dizendo
     * a mesma coisa: "Hoje tem Treino A" sai quando o treino de hoje é trocado para outro dia (hoje virou
     * descanso, e a motivação ligada faria o tipo "treino" virar a nota de descanso) e "Qualquer registro
     * hoje mantém a sequência" sai quando o registro de hoje fecha um marco (o recado vira comemoração).
     * O app usa isto para tirar da barra o que ficou velho.
     */
    fun stillRelevant(kinds: Set<ReminderKind>, postedOn: LocalDate?, s: ReminderSettings, ctx: ReminderContext, postedTitle: String? = null): Boolean {
        if (!ctx.hasProfile || postedOn != ctx.now.toLocalDate()) return false
        val primary = kinds.minByOrNull { it.ordinal } ?: return false
        val n = s.normalized()
        if (!switchedOn(primary, n, ctx)) return false
        val current = message(primary, n, ctx) ?: return false
        return postedTitle == null || current.title == postedTitle
    }

    /** Quanto tempo uma notificação postada em [now] vale: até a meia-noite (o texto fala de "hoje"). */
    fun lifetime(now: LocalDateTime): Duration = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())

    /** Lembrete de teste: o que os lembretes ligados diriam agora, ou um exemplo genérico. */
    fun preview(s: ReminderSettings, ctx: ReminderContext): ReminderMessage {
        val kinds = ReminderKind.values().filter {
            when (it) {
                ReminderKind.WATER -> s.waterEnabled
                ReminderKind.WORKOUT -> s.workoutEnabled
                ReminderKind.MOTIVATION -> s.motivationEnabled
            }
        }.ifEmpty { ReminderKind.values().toList() }.toSet()
        val msg = if (ctx.hasProfile) compose(withRestNote(s, kinds, ctx).mapNotNull { message(it, s.copy(motivationEnabled = true), ctx) }) else null
        return msg ?: ReminderMessage(ReminderKind.MOTIVATION, "Lembrete de teste",
            "👑 Tudo certo por aqui! É assim que os lembretes do FitKingIA vão aparecer — só quando fizer sentido.")
    }

    fun message(kind: ReminderKind, s: ReminderSettings, ctx: ReminderContext): ReminderMessage? = when (kind) {
        ReminderKind.WATER -> water(s, ctx)?.let { ReminderMessage(kind, kind.title, it) }
        ReminderKind.WORKOUT -> workout(s, ctx)?.let { (title, text) ->
            ReminderMessage(kind, title, text, coversStreak = ctx.workoutDay == WorkoutDay.PENDING)
        }
        ReminderKind.MOTIVATION -> motivation(ctx)?.let { (title, text) -> ReminderMessage(kind, title, text) }
    }

    /**
     * Vários tipos no mesmo horário viram uma notificação só. O recado da sequência só sai quando outra
     * parte já chama para ela (o treino pendente: "Bora manter a sequência…"); com descanso ou treino
     * aberto, as duas linhas ficam.
     */
    fun compose(parts: List<ReminderMessage>): ReminderMessage? {
        if (parts.isEmpty()) return null
        val sorted = parts.sortedBy { it.kind.ordinal }
        val kept = if (sorted.any { it.coversStreak }) sorted.filter { it.kind != ReminderKind.MOTIVATION } else sorted
        val first = kept.first()
        if (kept.size == 1) return first
        return first.copy(
            text = kept.joinToString("\n") { it.text },
            kinds = kept.flatMap { it.kinds }.toCollection(linkedSetOf()),
            coversStreak = kept.any { it.coversStreak },
        )
    }

    // -------------------------------------------------------------------------------------
    // Água
    // -------------------------------------------------------------------------------------

    /** Quanto já deveria ter bebido até [now] se a meta fosse distribuída por igual na janela. */
    fun expectedWaterMl(s: ReminderSettings, targetMl: Int, now: LocalTime): Int {
        val n = s.normalized()
        val total = (n.windowEnd - n.windowStart) * 60
        val elapsed = (now.hour * 60 + now.minute - n.windowStart * 60).coerceIn(0, total)
        return if (total == 0) targetMl else (targetMl.toLong() * elapsed / total).toInt()
    }

    private val waterNudges = listOf(
        "Um copo agora te deixa no ritmo!",
        "Que tal uma pausa para um copo d'água?",
        "Um gole agora e você fica em dia com a meta.",
        "Bora de um copo? Pequenos goles ao longo do dia somam.",
    )

    fun water(s: ReminderSettings, ctx: ReminderContext): String? {
        val target = ctx.waterTargetMl ?: return null
        val consumed = ctx.waterConsumedMl ?: 0
        if (target <= 0 || consumed >= target) return null
        if (consumed >= expectedWaterMl(s, target, ctx.now.toLocalTime())) return null
        if (consumed == 0) return "💧 Nenhum copo registrado hoje ainda (meta: ${Fmt.int(target)} ml). Que tal começar agora?"
        return "💧 ${Fmt.int(consumed)} de ${Fmt.int(target)} ml até agora. ${pick(waterNudges, ctx.now)}"
    }

    // -------------------------------------------------------------------------------------
    // Treino do dia
    // -------------------------------------------------------------------------------------

    private val restNotes = listOf(
        "🌿 Hoje é dia de descanso. Recuperar também faz parte do progresso.",
        "😌 Dia de descanso: aproveite para dormir bem e chegar inteiro ao próximo treino.",
        "🌿 Descanso hoje. O treino de amanhã agradece.",
    )

    fun workout(s: ReminderSettings, ctx: ReminderContext): Pair<String, String>? = when (ctx.workoutDay) {
        WorkoutDay.PENDING -> {
            val title = ctx.sessionTitle ?: "seu treino"
            val minutes = ctx.sessionMinutes?.takeIf { it > 0 }?.let { " (~$it min)" }.orEmpty()
            ReminderKind.WORKOUT.title to "🏋️ Hoje tem $title$minutes. ${streakCall(ctx)}"
        }
        WorkoutDay.IN_PROGRESS ->
            ReminderKind.WORKOUT.title to "🏋️ Seu treino de hoje${ctx.sessionTitle?.let { " ($it)" }.orEmpty()} ficou aberto. Dá para retomar de onde parou."
        WorkoutDay.REST -> if (s.motivationEnabled) "Dia de descanso" to pick(restNotes, ctx.now) else null
        WorkoutDay.DONE, WorkoutDay.NO_PROGRAM -> null
    }

    private fun streakCall(ctx: ReminderContext): String {
        val n = ctx.streak
        return if (n >= 2) pick(listOf("Bora manter a sequência de $n dias?", "Sua sequência está em $n dias — bora somar mais um?"), ctx.now)
        else pick(listOf("Bora começar bem?", "Cada treino conta. Bora?"), ctx.now)
    }

    // -------------------------------------------------------------------------------------
    // Motivação e sequência
    // -------------------------------------------------------------------------------------

    fun motivation(ctx: ReminderContext): Pair<String, String>? {
        val n = ctx.streak
        return when {
            ctx.activeToday -> if (n in STREAK_MILESTONES) "Sequência de $n dias" to pick(listOf(
                "🏆 $n dias seguidos! Constância é o que traz resultado.",
                "🏆 $n dias seguidos. Você está construindo um hábito de verdade!",
            ), ctx.now) else null
            n >= 2 -> ReminderKind.MOTIVATION.title to "🔥 Sua sequência está em $n dias. Qualquer registro hoje mantém ela viva — até um copo d'água conta."
            n == 1 -> ReminderKind.MOTIVATION.title to "🔥 Ontem você começou uma sequência. Um registro hoje já faz dela 2 dias!"
            else -> ReminderKind.MOTIVATION.title to pick(listOf(
                "✨ Um passo pequeno hoje já conta: registre um copo d'água, uma refeição ou o treino.",
                "✨ Bora começar uma sequência nova? Um registro hoje já é o primeiro dia.",
            ), ctx.now)
        }
    }

    /** Rotação determinística: muda com o dia e com a hora, igual para as mesmas entradas. */
    fun <T> pick(options: List<T>, at: LocalDateTime): T = options[(at.dayOfYear + at.hour) % options.size]
}

/** Liga os lembretes ao app: lê e grava as preferências e monta o retrato do momento a partir do [FitKing]. */
object Reminders {
    fun settings(fit: FitKing): ReminderSettings = ReminderSettings.load(fit.repo)

    fun save(fit: FitKing, s: ReminderSettings): Boolean = s.save(fit.repo)

    fun next(fit: FitKing, after: LocalDateTime = fit.clock.now()): ReminderSlot? = ReminderPlanner.next(settings(fit), after)

    fun context(fit: FitKing): ReminderContext {
        val now = fit.clock.now()
        if (!fit.hasProfile()) return ReminderContext(now, hasProfile = false)
        val water = fit.water()
        val today = fit.todayView()
        val unfinished = today?.unfinished?.takeIf { it.startedAt.toLocalDate() == now.toLocalDate() }
        val day = when {
            today == null -> WorkoutDay.NO_PROGRAM
            unfinished != null -> WorkoutDay.IN_PROGRESS
            today.today.status == DayStatus.TODAY && today.today.session != null -> WorkoutDay.PENDING
            today.today.status == DayStatus.DONE -> WorkoutDay.DONE
            else -> WorkoutDay.REST
        }
        val title = when (day) {
            WorkoutDay.IN_PROGRESS -> unfinished?.plan?.name
            else -> today?.title ?: today?.today?.session?.name
        }
        val minutes = today?.session?.estimatedMinutes ?: today?.today?.session?.estimatedMinutes
        return ReminderContext(
            now = now,
            waterConsumedMl = water?.progress?.consumedMl,
            waterTargetMl = water?.target?.totalMl,
            workoutDay = day,
            sessionTitle = title,
            sessionMinutes = minutes,
            streak = fit.streak(),
            activeToday = now.toLocalDate() in fit.repo.activeDays(),
        )
    }

    /** O que notificar no disparo de [slot] (null = nada). */
    fun messageAt(fit: FitKing, slot: LocalDateTime): ReminderMessage? {
        val s = settings(fit)
        if (ReminderPlanner.due(s, slot).isEmpty()) return null
        return ReminderPlanner.messageAt(s, slot, context(fit))
    }

    fun preview(fit: FitKing): ReminderMessage = ReminderPlanner.preview(settings(fit), context(fit))

    /** A notificação postada em [postedOn] com [kinds] e [postedTitle] ainda vale agora? (senão o app a retira da barra) */
    fun stillRelevant(fit: FitKing, kinds: Set<ReminderKind>, postedOn: LocalDate?, postedTitle: String? = null): Boolean =
        ReminderPlanner.stillRelevant(kinds, postedOn, settings(fit), context(fit), postedTitle)

    // -------------------------------------------------------------------------------------
    // Convite (tela Hoje): os lembretes vêm desligados; depois do primeiro programa o app
    // pergunta uma vez se a pessoa quer ligá-los. Nada liga sem um toque em "Ligar".
    // -------------------------------------------------------------------------------------

    fun showInvite(fit: FitKing): Boolean =
        fit.hasProfile() && fit.repo.pref(ReminderSettings.KEY_INVITE) == null && !settings(fit).anyEnabled && fit.program() != null

    /**
     * "Ligar": água no ritmo da meta e o treino do dia, com os horários que já estavam salvos (padrão:
     * 8h–21h, treino às 18h). Null se não há perfil onde guardar.
     */
    fun acceptInvite(fit: FitKing): ReminderSettings? {
        val s = settings(fit).copy(waterEnabled = true, workoutEnabled = true)
        if (!save(fit, s)) return null
        fit.repo.setPref(ReminderSettings.KEY_INVITE, "accepted")
        return settings(fit)
    }

    /** "Agora não" (ou permissão negada): não pergunta de novo; Mais › Lembretes continua disponível. */
    fun dismissInvite(fit: FitKing) {
        if (fit.hasProfile()) fit.repo.setPref(ReminderSettings.KEY_INVITE, "dismissed")
    }
}
