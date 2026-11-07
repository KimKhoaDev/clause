# Clause

Android client for Claude in the Claude look (cream/clay palette, serif replies), with liquid-glass UI from
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) and device control through **Shizuku**.

## Run
1. Open in a recent Android Studio (AGP 9.x, JDK 17+) and run `app` (API 26+).
2. Sign in with an Anthropic API key (console.anthropic.com).

## Device control (Shizuku over Wireless debugging)
1. Install Shizuku. 2. Settings -> Developer options -> Wireless debugging ON.
3. In Shizuku: Pairing -> enter the code, then Start. 4. In Clause: Settings -> Device control -> Grant access.
5. In a chat tap **🛠 Device**.

Tools: shell, read/write/edit files, list/copy/move/delete, list/open apps, app control (stop, clear, disable, grant/revoke, uninstall),
Android settings get/put, tap/swipe/type/keys, screen reading (UI dump + screenshot), device info, open URL, **download** (SHA-256 verified),
**silent APK install**, logcat.

Safety: approval sheet before actions (Ask / Auto-approve safe / Full auto), risk levels, allowed-folder list, hard-blocked catastrophic
commands, per-file backups with **Undo/Keep**, full activity log, step limit, untrusted-content warning in the system prompt.

## AI providers
Anthropic, OpenRouter, Kilo Gateway (api.kilo.ai/api/gateway), Google Gemini (API key), OpenAI, Groq, DeepSeek, Mistral, xAI, Ollama and any
custom OpenAI-compatible endpoint. Switch provider/model from the pill at the top of a chat (fetch + search models, or type any id).
Device-control tools work on every provider (tool calls are converted). Web search is Anthropic-only. "Enter without verifying" skips the key test.
Google Antigravity sign-in is intentionally not included: it relies on private endpoints. Use a Gemini API key instead.

## Auto mode
The ⚡ Auto chip runs device actions without asking for the current chat (resets on a new chat). Catastrophic commands stay blocked and changes stay undoable.

## Customisation
Theme, accent colour (presets + hex), reply font, text size, background glow, and every liquid-glass parameter (blur, refraction edge/strength,
opacity, vibrancy) with a live preview.

## Other features
Streaming chat, web search, attachments, voice dictation + read-aloud, hands-free voice conversation, projects, memory, custom instructions,
artifacts (HTML/SVG), SVG image generation, scheduled prompts, auto-send after usage limits, file-based cross-device sync.

## Credits
Design ideas on checkpoints/undo, bounded output, and verified downloads/APK installs were inspired by
[Mobile-Harness](https://github.com/techjarves/Mobile-Harness) (MIT). No code was copied; its PRoot/Linux runtime is not used.
