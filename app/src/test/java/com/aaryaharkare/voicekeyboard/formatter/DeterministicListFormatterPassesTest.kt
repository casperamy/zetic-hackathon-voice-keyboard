package com.aaryaharkare.voicekeyboard.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicListFormatterPassesTest {

    private val formatter = DeterministicListFormatter()

    @Test
    fun `normalizes numbered list into ordered list`() {
        val input =
            """
            1. Define the scope.
            2. Freeze the benchmark.
            3. Ship the fix.
            """.trimIndent()

        assertEquals(
            """
            1. Define the scope.
            2. Freeze the benchmark.
            3. Ship the fix.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `supports multiline continuation inside ordered list item`() {
        val input =
            """
            1. Prepare the release.
            2. Validate the payment flow
               across sandbox and production.
            3. Publish the changelog.
            """.trimIndent()

        assertEquals(
            """
            1. Prepare the release.
            2. Validate the payment flow across sandbox and production.
            3. Publish the changelog.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `extracts inferred prose list items from supported lead in`() {
        val analysis = formatter.analyze("Growth depends on acquisition, activation, retention, and revenue.")
        val decision = analysis.paragraphAnalyses.single().candidateDecisions.single()

        assertTrue(decision.accepted)
        assertEquals(DeterministicListFormatter.CandidateType.INFERRED_PROSE, decision.type)
        assertEquals(listOf("acquisition", "activation", "retention", "revenue"), decision.items)
    }

    @Test
    fun `rejects protected title and address preambles`() {
        val titleAnalysis = formatter.analyze("Her title is Director, Product Strategy, at the new company.")
        val titleDecision = titleAnalysis.paragraphAnalyses.single().candidateDecisions.single()
        assertFalse(titleDecision.accepted)
        assertEquals(DeterministicListFormatter.RejectionReason.PROTECTED_PREAMBLE, titleDecision.rejectionReason)

        val addressAnalysis = formatter.analyze("My address is 240 King Street, Apartment 6B, Seattle, Washington.")
        val addressDecision = addressAnalysis.paragraphAnalyses.single().candidateDecisions.single()
        assertFalse(addressDecision.accepted)
        assertEquals(DeterministicListFormatter.RejectionReason.PROTECTED_PREAMBLE, addressDecision.rejectionReason)
    }

    @Test
    fun `rejects clause like comma chains`() {
        val analysis = formatter.analyze("The build was green, the dashboard was red, and nobody trusted either one.")
        val decision = analysis.paragraphAnalyses.single().candidateDecisions.single()

        assertFalse(decision.accepted)
        assertEquals(DeterministicListFormatter.RejectionReason.CLAUSE_LIKE_ITEMS, decision.rejectionReason)
    }

    @Test
    fun `splits trailing and into separate list items`() {
        val analysis = formatter.analyze("The shortlist includes design polish, billing fixes, onboarding cleanup, and support macros.")
        val decision = analysis.paragraphAnalyses.single().candidateDecisions.single()

        assertTrue(decision.accepted)
        assertEquals(
            listOf("design polish", "billing fixes", "onboarding cleanup", "support macros"),
            decision.items,
        )
    }

    @Test
    fun `formats cross sentence intro and bare list`() {
        val input = "There are four things that are important. Name, place, animal, and thing."

        assertEquals(
            """
            There are four things that are important:
            • Name
            • place
            • animal
            • thing
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `formats sentence run list from intro sentence`() {
        val input =
            "The most important things in this app are first of all. It's local. It's cheap. It costs zero dollars. It's fast. And it's going to make me 700 dollars."

        assertEquals(
            """
            The most important things in this app are:
            • It's local.
            • It's cheap.
            • It costs zero dollars.
            • It's fast.
            • It's going to make me 700 dollars.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `stops sentence run list before later plain prose`() {
        val input = "Here are the next steps. Freeze scope. Rerun QA. Ship the build. Now we can wait."

        assertEquals(
            """
            Here are the next steps:
            • Freeze scope.
            • Rerun QA.
            • Ship the build.
            
            Now we can wait.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `keeps prose before sentence run list plain`() {
        val input = "We tested the build yesterday. Here are the next steps. Freeze scope. Ship the build. Send notes."

        assertEquals(
            """
            We tested the build yesterday.
            
            Here are the next steps:
            • Freeze scope.
            • Ship the build.
            • Send notes.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `protected sentence breaks sentence run list`() {
        val input = "Here are the next steps. Freeze scope. Ship the build. Email jane.doe@example.com after that."

        assertEquals(
            """
            Here are the next steps:
            • Freeze scope.
            • Ship the build.
            
            Email jane.doe@example.com after that.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `removes filler prefix before formatting a list`() {
        val input = "Okay, there are four things that are important. Name, place, animal, and thing."

        assertEquals(
            """
            There are four things that are important:
            • Name
            • place
            • animal
            • thing
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `does not rewrite compiled during cleanup`() {
        val input = "The code compiled, the test flaked, and the deploy stalled."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `does not rewrite server during cleanup`() {
        val input = "The server rebooted, the queue drained, and the alarm finally cleared."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps email punctuation intact`() {
        val input = "My email is jane.doe@example.com, not the old alias."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps URL punctuation intact`() {
        val input = "The backup URL is https://api.example.com/v1/status, not the staging one."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps version punctuation intact`() {
        val input = "Version 1.2 shipped before 2.0, and both releases were messy."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps decimal punctuation intact`() {
        val input = "The reading changed from 3.14 to 2.71, and then settled at 1.62."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps protected address sentence plain`() {
        val input = "The office is on 8th Avenue, Floor 12, New York, New York."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `formats focused on lead in`() {
        val input = "The interview focused on constraints, incentives, timelines, and ownership."

        assertEquals(
            """
            The interview focused on:
            • constraints
            • incentives
            • timelines
            • ownership
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `formats highlighted lead in`() {
        val input = "The call highlighted procurement delays, staffing risk, scope drift, and vague ownership."

        assertEquals(
            """
            The call highlighted:
            • procurement delays
            • staffing risk
            • scope drift
            • vague ownership
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `formats concern lead in without dropping first item`() {
        val input = "The open questions concern rollout timing, beta scope, staffing needs, and legal review."

        assertEquals(
            """
            The open questions concern:
            • rollout timing
            • beta scope
            • staffing needs
            • legal review
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `keeps office location sentence plain`() {
        val input = "The office in San Francisco, California, closes at six."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `keeps transition led action sentence plain`() {
        val input = "Then he restarted the phone, rejoined the call, and finished as if nothing had happened."
        assertEquals(input, formatter.formatText(input))
    }

    @Test
    fun `formats embedded numbered run with prose before and after`() {
        val input =
            "The way that we have to build this plan is very constructively where everything that we think about has to be thought in a certain way. There are 5 aspects to this: 1. Normal. 2. Expert. 3. Developer. 4. Product Manager. 5. CEO. All of these combined give us a total output of 5 things."

        assertEquals(
            """
            The way that we have to build this plan is very constructively where everything that we think about has to be thought in a certain way.

            There are 5 aspects to this:
            1. Normal.
            2. Expert.
            3. Developer.
            4. Product Manager.
            5. CEO.

            All of these combined give us a total output of 5 things.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `formats the custom threaded numbered text exactly`() {
        val input =
            """
            The way that we have to build this plan is very constructively where everything that we think about has to be thought in a certain way.
            There are 5 aspects to this:
            1. Normal
            2. Expert
            3. Developer
            4. Product Manager
            5. CEO
            All of these combined give us a total output of 5 things. Now I want to see if all of these 5 things are as a list or if there are some false positives in the list as well because there can be certain things which are false positives as well.
            """.trimIndent()

        assertEquals(
            """
            The way that we have to build this plan is very constructively where everything that we think about has to be thought in a certain way.

            There are 5 aspects to this:
            1. Normal
            2. Expert
            3. Developer
            4. Product Manager
            5. CEO

            All of these combined give us a total output of 5 things. Now I want to see if all of these 5 things are as a list or if there are some false positives in the list as well because there can be certain things which are false positives as well.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `formats clean inline numbered sequence as ordered list`() {
        val input = "There are 3 aspects to this: 1. Normal. 2. Expert. 3. Developer."

        assertEquals(
            """
            There are 3 aspects to this:
            1. Normal.
            2. Expert.
            3. Developer.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `falls back to bullets for inconsistent numbered run`() {
        val input = "There are a few concerns: 1. Scope. 2. Latency. 4. Rollout."

        assertEquals(
            """
            There are a few concerns:
            • Scope.
            • Latency.
            • Rollout.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `falls back to bullets when numbering starts at two`() {
        val input = "There are a few concerns: 2. Scope. 3. Latency. 4. Rollout."

        assertEquals(
            """
            There are a few concerns:
            • Scope.
            • Latency.
            • Rollout.
            """.trimIndent(),
            formatter.formatText(input),
        )
    }

    @Test
    fun `keeps protected numeric text plain instead of parsing numbered run`() {
        val input = "Version 1.2.3 shipped before 2.0.1, and the hotfix landed on 03/11/2026."
        assertEquals(input, formatter.formatText(input))
    }

}
