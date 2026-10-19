# yutaplugins (fork)

Fork of [yutaplug/yutaplugins](https://github.com/yutaplug/yutaplugins) for [Aliucord](https://github.com/Aliucord/Aliucord).

## TranslateMessages 1.1.0

Everything the original plugin does, plus:

- **Translate your own messages before sending.** Type in your language and the message is sent already translated (English by default, any language selectable).
- **Mentions, emojis, links, timestamps and code blocks stay untouched**, the same as with incoming translations.
- **Nothing is sent if translation fails.** You get a notice and your text stays in the chat box, so a message never goes out untranslated by mistake.
- **Works with every translation service** in the plugin: Google Translate (free, no key), DeepL and LibreTranslate.
- **Edits and `/` commands are left alone.**

Enable it in the plugin settings → *Outgoing messages* → *Translate my messages before sending*, and pick the language in *Send in*.

### Install

1. Download [TranslateMessages.zip](https://raw.githubusercontent.com/sneal4you/yutaplugins/builds/TranslateMessages.zip).
2. Move it to `Internal storage/Aliucord/plugins/`, replacing the original `TranslateMessages.zip` if present.
3. Fully close Discord and reopen it.

Updates arrive through Aliucord's plugin updater.
