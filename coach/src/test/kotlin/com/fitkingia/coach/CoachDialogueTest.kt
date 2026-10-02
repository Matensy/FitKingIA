package com.fitkingia.coach

import com.fitkingia.coach.CoachFixture.context
import com.fitkingia.coach.nlu.Intent
import kotlin.test.*

class CoachDialogueTest {
    private val coach = LocalCoach(CoachFixture.kb)
    // Fora das funções: lambdas dentro de testes com nome acentuado geram arquivos .class com acento.
    private val learningCoach = LocalCoach(CoachFixture.kb)
    private fun chat(vararg messages: String): List<CoachReply> {
        val state = ConversationState()
        return messages.map { coach.reply(it, context(), state) }
    }

    @Test fun `sinais de alerta interrompem tudo e orientam atendimento`() {
        for (msg in listOf("senti dor no peito no supino", "quase desmaiei no agachamento", "to com falta de ar e palpitação")) {
            val r = chat(msg).single()
            assertNull(r.intent, msg)
            assertTrue("192" in r.text && "Pare o exercício" in r.text, msg)
        }
    }

    @Test fun `não orienta hormônios nem estratégias perigosas`() {
        assertTrue("não oriento" in chat("qual ciclo de anabolizante tomar").single().text.lowercase())
        assertTrue("CVV" in chat("vou ficar dias sem comer pra secar").single().text)
    }

    @Test fun `tenho 35 minutos adapta a sessão e explica cortes`() {
        val r = chat("tenho só 35 minutos hoje").single()
        assertEquals(Intent.SHORT_ON_TIME, r.intent)
        assertTrue("QUICK SESSION" in r.text)
        assertTrue("O que mudou e por quê" in r.text)
    }

    @Test fun `pergunta o dado que falta e continua a conversa`() {
        val (ask, answer) = chat("hoje estou sem tempo", "uns 30 minutos")
        assertEquals(Intent.SHORT_ON_TIME, ask.intent)
        assertTrue("Quanto tempo" in ask.text)
        assertEquals(Intent.SHORT_ON_TIME, answer.intent)
        assertTrue("30 minutos" in answer.text && "QUICK SESSION" in answer.text)
    }

    @Test fun `lembra o exercício da conversa para perguntas seguintes`() {
        val (how, swap, why) = chat("como faço stiff?", "e se a barra estiver ocupada, troca por outro", "por que ele está no meu treino?")
        assertEquals(Intent.EXERCISE_HOWTO, how.intent)
        assertTrue("Levantamento terra romeno" in how.text)
        assertEquals(Intent.SUBSTITUTE, swap.intent)
        assertTrue("no lugar de Levantamento terra romeno" in swap.text, swap.text)
        assertTrue(why.intent == Intent.EXPLAIN_EXERCISE)
    }

    @Test fun `dor no joelho não diagnostica e oferece alternativas`() {
        val r = chat("meu joelho está doendo no agachamento").single()
        assertEquals(Intent.PAIN, r.intent)
        assertTrue("Não consigo diagnosticar" in r.text)
        assertTrue("→" in r.text)
        val strong = chat("dor forte no joelho, 8/10").single()
        assertTrue("procure um profissional" in strong.text)
    }

    @Test fun `cansaço vira Recovery Score e treino ajustado`() {
        val r = chat("hoje estou cansado, dormi mal").single()
        assertEquals(Intent.TIRED, r.intent)
        assertTrue("Recovery Score" in r.text)
        assertTrue("Faça o check-in completo" in r.text)
    }

    @Test fun `progressão usa o histórico do usuário`() {
        val r = chat("quanto peso coloco no supino?").single()
        assertEquals(Intent.PROGRESSION, r.intent)
        assertTrue("Sugestão baseada nas suas sessões anteriores" in r.text, r.text)
        assertTrue("62,5 kg" in r.text)
    }

    @Test fun `dúvida científica responde com nível de evidência e fonte, ou admite não saber`() {
        val r = chat("treinar até a falha é melhor para hipertrofia?").single()
        assertEquals(Intent.EVIDENCE, r.intent)
        assertTrue("Nível:" in r.text && "✔" in r.text)
        val unknown = coach.reply("o que a ciência diz sobre treinar em jejum de manhã com música alta", context(), ConversationState())
        if (unknown.intent == Intent.EVIDENCE) assertTrue("não inventar" in unknown.text || "Nível:" in unknown.text)
    }

    @Test fun `perder barriga explica a evidência conflitante e o que funciona`() {
        val r = chat("como perder barriga?").single()
        assertEquals(Intent.SPOT_REDUCTION, r.intent)
        assertTrue("conflitante" in r.text)
        assertTrue("déficit calórico" in r.text)
    }

    @Test fun `refeição em linguagem natural propõe registro`() {
        val r = chat("hoje comi arroz, feijão, frango e banana").single()
        assertEquals(Intent.MEAL, r.intent)
        assertTrue("Arroz" in r.text && "Total:" in r.text)
        assertIs<CoachAction.LogMeal>(r.actions.single())
    }

    @Test fun `água registrada vira ação e progresso`() {
        val r = chat("bebi 500 ml de água").single()
        assertEquals(Intent.WATER, r.intent)
        assertEquals(CoachAction.LogWater(500), r.actions.single())
        assertTrue("💧 500 /" in r.text)
    }

    @Test fun `ferramentas numéricas`() {
        assertTrue("25 kg + 5 kg + 1,25 kg" in chat("quero colocar 82,5 kg na barra").single().text)
        val rm = chat("fiz 100 kg x 5, qual meu 1rm").single()
        assertEquals(Intent.ONE_RM, rm.intent)
        assertTrue("Epley 116,7 kg" in rm.text, rm.text)
    }

    @Test fun `treino perdido oferece opções A a D sem culpa`() {
        val r = chat("faltei o treino de segunda").single()
        assertEquals(Intent.MISSED, r.intent)
        assertTrue("Sem culpa" in r.text && "A —" in r.text && "D —" in r.text)
    }

    @Test fun `e se eu treinar 5 dias simula com o motor`() {
        val r = chat("e se eu treinar 5 dias por semana?").single()
        assertEquals(Intent.SIMULATE, r.intent)
        assertTrue("Atual" in r.text && "5D" in r.text && "Tempo/semana" in r.text)
    }

    @Test fun `encontrar exercício em casa respeita a condição`() {
        val r = chat("exercício de glúteo em casa sem equipamento").single()
        assertEquals(Intent.FIND_EXERCISE, r.intent)
        assertTrue("sem equipamento" in r.text)
        assertFalse("Leg press" in r.text)
    }

    @Test fun `quando não tem certeza pergunta, e aprende com a escolha`() {
        val state = ConversationState()
        val c = learningCoach
        val vague = "sobre o negócio da cintura e da balança"
        val first = c.reply(vague, context(), state)
        if (first.intent == null && state.awaitingAnswer) {
            val chosen = c.reply("1", context(), state)
            assertNotNull(chosen.intent)
            // aprendeu: a mesma frase agora é entendida direto
            val again = c.reply(vague, context(), ConversationState())
            assertEquals(chosen.intent, again.intent)
        }
    }

    @Test fun `cumprimento, ajuda e agradecimento`() {
        val hi = chat("oi").single()
        assertTrue(hi.text.startsWith("🟣 Oi, Matheus!"), hi.text)
        val help = chat("o que você sabe fazer?").single()
        assertTrue("offline" in help.text, "${help.intent}: ${help.text}")
        assertEquals(Intent.THANKS, chat("valeu!").single().intent)
    }

    @Test fun `toda resposta marca a proveniência`() {
        val msgs = listOf("qual meu treino hoje", "por que faço supino", "creatina funciona", "qual meu imc", "volume semanal")
        for (m in msgs) {
            val r = chat(m).single()
            assertTrue(listOf("🟣", "🟢", "🔵").any { it in r.text }, m)
        }
    }
}
