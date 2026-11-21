# Antigravity Mobile (unofficial)

Independent native Android coding workspace. No Google desktop binaries are redistributed. Not affiliated with Google.

## Features
- Android Storage Access Framework: select a workspace, persist permission, navigate directories, create/rename/delete files and folders, edit and save UTF-8 files directly in the selected directory.
- Native editor with undo, redo, find, clipboard, and dirty-file protection.
- Optional Gemini API assistant using a user-provided API key (in memory only). Explicit confirmation before sending the open file to the API or replacing its contents.
- GitHub Actions debug APK build, lint, and artifact upload.

## Build
JDK 17, Gradle 8.9, Android SDK 35. Run `gradle :app:assembleDebug`, or use Actions > Android APK > Run workflow. Android 8+.

## Limitations
This is an independent implementation, not a binary port. No official Antigravity agent or login, arbitrary shell, Git client, browser automation, multi-file agent, or extension host yet. Android's picker enforces OS storage restrictions. Editor supports UTF-8 text files up to 2 MiB. AI requires internet, a valid API key and model ID. Review AI outputs before applying. No API key is saved.
