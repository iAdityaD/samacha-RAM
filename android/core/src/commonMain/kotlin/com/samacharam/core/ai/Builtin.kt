package com.samacharam.core.ai

/** Free, offline baselines. Tasks without a meaningful rule-based version fail instead of faking output. */
object Builtin {
    private val KEYWORDS = mapOf(
        "LLMs" to listOf("language model", "llm", "transformer", "retrieval", "reasoning"),
        "NLP" to listOf("language", "translation", "nlp", "text"),
        "AI audio" to listOf("speech", "audio", "voice"),
        "AI video" to listOf("video", "diffusion", "vision"),
        "Hardware" to listOf("gpu", "chip", "hardware", "cuda", "accelerator"),
        "Software" to listOf("code", "software", "python", "pytorch", "database"),
    )

    fun run(task: AiTask, input: TaskInput): TaskOutput = when (task) {
        AiTask.Classification -> classify(input)
        AiTask.Summarization -> {
            val picked = sentences(input.text).filter { it.length > 30 }.take(3).ifEmpty { listOf(input.text.trim().take(600)) }
            TaskOutput.Cited(picked.map { CitedSentence(it, it) }, "Key sentences from the source, not a generated summary.")
        }
        AiTask.QA -> {
            val words = words(input.question)
            val matches = sentences(input.text).map { it to (words intersect words(it)).size }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(2).map { it.first }
            TaskOutput.Cited(matches.map { CitedSentence(it, it) }, "Matching passages from the source, not a generated answer.")
        }
        AiTask.Embeddings, AiTask.Reranking, AiTask.Evidence ->
            throw AiException("${task.wire} needs a connected model. No placeholder scores or verdicts are generated.")
    }

    /** Keyword rules for the default topics, plus a whole-word match on any topic name the owner created. */
    private fun classify(input: TaskInput): TaskOutput.Labels {
        val text = input.text.lowercase()
        val labels = input.labels.filter { label ->
            val keywords = KEYWORDS[label].orEmpty() + label.lowercase()
            keywords.any { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])").containsMatchIn(text) }
        }
        return TaskOutput.Labels(labels, "Keyword rules; not a learned classifier.")
    }

    private fun sentences(text: String) = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]{4,}").findAll(text.lowercase()).map { it.value }.toSet()
}
