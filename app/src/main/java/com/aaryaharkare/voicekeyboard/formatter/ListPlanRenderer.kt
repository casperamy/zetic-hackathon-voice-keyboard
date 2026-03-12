package com.aaryaharkare.voicekeyboard.formatter

internal object ListPlanRenderer {
    fun render(
        text: String,
        plans: List<ListPlan>,
    ): String {
        if (plans.isEmpty()) return text.trim()

        val sortedPlans = plans.sortedBy { it.startIndex ?: Int.MAX_VALUE }
        val builder = StringBuilder()
        var cursor = 0
        var previousWasBlock = false

        sortedPlans.forEach { plan ->
            val startIndex = plan.startIndex ?: return@forEach
            val endIndex = plan.endIndex ?: return@forEach
            val plainSegment = text.substring(cursor, startIndex)

            if (previousWasBlock) {
                appendPlainAfterBlock(builder, plainSegment)
            } else {
                appendPlainBeforeBlock(builder, plainSegment)
            }

            appendBlock(builder, renderBlock(plan))
            previousWasBlock = true
            cursor = endIndex
        }

        val tail = text.substring(cursor)
        if (previousWasBlock) {
            appendPlainAfterBlock(builder, tail)
        } else {
            appendPlainBeforeBlock(builder, tail)
        }

        return builder.toString().trim()
    }

    private fun renderBlock(plan: ListPlan): String {
        val header = plan.introText.takeIf { it.isNotBlank() }?.let { "$it:" }
        val items =
            plan.items.mapIndexed { index, item ->
                when (plan.listType) {
                    ListType.ORDERED -> "${index + 1}. $item"
                    ListType.UNORDERED -> "• $item"
                }
            }

        return buildString {
            if (header != null) {
                append(header)
                append('\n')
            }
            append(items.joinToString(separator = "\n"))
        }
    }

    private fun appendPlainBeforeBlock(
        builder: StringBuilder,
        plainSegment: String,
    ) {
        if (plainSegment.isEmpty()) return

        if (builder.isEmpty()) {
            builder.append(plainSegment.trimStart().trimEnd())
            return
        }

        builder.append(plainSegment.trimEnd())
    }

    private fun appendPlainAfterBlock(
        builder: StringBuilder,
        plainSegment: String,
    ) {
        val normalized = plainSegment.trimStart()
        if (normalized.isEmpty()) return

        trimTrailingWhitespace(builder)
        if (builder.isNotEmpty() && !builder.endsWith("\n\n")) {
            if (builder.endsWith("\n")) {
                builder.append('\n')
            } else {
                builder.append("\n\n")
            }
        }
        builder.append(normalized)
    }

    private fun appendBlock(
        builder: StringBuilder,
        block: String,
    ) {
        trimTrailingWhitespace(builder)
        if (builder.isNotEmpty() && !builder.endsWith("\n\n")) {
            if (builder.endsWith("\n")) {
                builder.append('\n')
            } else {
                builder.append("\n\n")
            }
        }
        builder.append(block.trim())
    }

    private fun trimTrailingWhitespace(builder: StringBuilder) {
        while (builder.isNotEmpty() && builder.last().isWhitespace()) {
            builder.deleteCharAt(builder.length - 1)
        }
    }

    private fun StringBuilder.endsWith(suffix: String): Boolean {
        if (length < suffix.length) return false
        return substring(length - suffix.length, length) == suffix
    }
}
