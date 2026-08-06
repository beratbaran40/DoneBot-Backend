package com.todoapp.backend.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * `ChatToolDeclarations`'s file header says the declarations and the two system instructions must stay
 * 1:1 and change in the same commit. Nothing enforced that, so a tool could be declared to the model
 * with the English prompt describing it and the Turkish one not — or, as happened, be listed as
 * "needs no confirmation" in both prompts while its declaration described a destructive branch.
 *
 * This is a plain resource-text test: no Spring context, no Vertex.
 */
class ChatPromptConsistencyTest {

    private val english = loadPrompt("chat/system-instruction-en.md")
    private val turkish = loadPrompt("chat/system-instruction-tr.md")

    @Test
    fun `every declared tool is described in both system instructions`() {
        val declared = ChatToolDeclarations.tool.functionDeclarationsList.map { it.name }

        assertThat(declared).isNotEmpty()
        assertThat(declared.filterNot { english.contains(it) })
            .describedAs("tools missing from the English prompt")
            .isEmpty()
        assertThat(declared.filterNot { turkish.contains(it) })
            .describedAs("tools missing from the Turkish prompt")
            .isEmpty()
    }

    @Test
    fun `setSteps is not listed as an unconditional no-confirmation action in either system instruction`() {
        // setSteps with an empty array deletes every step of a task, bypassing the last-step guard
        // deleteStep enforces. Listing it flatly among the reversible actions told the model a single
        // mis-parsed "clear that up" could destroy a checklist on the same turn, with no confirmation.
        listOf(english, turkish).forEach { prompt ->
            assertThat(prompt)
                .describedAs("the reversible list must qualify setSteps, not list it bare")
                .doesNotContain("finishRoutine, setSteps")
        }
        assertThat(english).contains("setSteps with a non-empty list")
        assertThat(turkish).contains("boş olmayan listeyle setSteps")
    }

    @Test
    fun `both prompts warn that an unparseable date is rejected rather than treated as a clear`() {
        // The two halves of the same rule: the server now refuses a phrase like "end of next month",
        // and the model has to know that only an empty value clears a field.
        assertThat(english).contains("REJECTED")
        assertThat(turkish).contains("REDDEDİLİR")
    }

    @Test
    fun `both prompts stay the same length so an edit to one is an edit to the other`() {
        // They are line-for-line translations. A drift in line count is the cheapest possible signal
        // that a rule was added to one prompt and forgotten in the other.
        assertThat(english.lines().size).isEqualTo(turkish.lines().size)
    }

    private fun loadPrompt(path: String): String =
        ClassPathResource(path).inputStream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
}
