# Formatter LLM Setup

Add these keys to `local.properties`:

```properties
PERSONAL_KEY=your_zetic_personal_key
FORMATTER_LLM_MODEL=palm/LFM2.5-1.2B-Instruct
FORMATTER_LLM_VERSION=1
FORMATTER_LLM_MODE=RUN_AUTO
```

App behavior:

- The IME uses the Zetic LLM formatter by default for format-eligible text fields.
- `Preload Formatter LLM` downloads and warms the configured formatter model before keyboard testing.
- Formatter failures are treated as hard failures. The IME does not fall back to another formatter for that utterance.

Fold 6 contingency:

```properties
FORMATTER_LLM_MODEL=SJ_zetic/functiongemma-270m-it
```

Only change the model key, then rerun the preload smoke test from the setup screen.
