# Pixel Gravity

[Русский](#русский) · [English](#english)

## Русский

**Порт твика Gravitation для Pixel Launcher.** При встряхивании ярлыки, папки, виджеты и поиск начинают двигаться под действием наклона телефона. При перелистывании каждый рабочий стол сохраняет позиции своих иконок.

### Установка и использование

1. Нужен штатный Pixel Launcher (`com.google.android.apps.nexuslauncher`) и работающий Xposed-фреймворк с поддержкой Legacy Xposed API. Если у тебя уже есть KernelSU, можно установить ZIP NeoZygisk и Vector через менеджер KernelSU, а затем перезагрузить телефон. [NeoZygisk](https://github.com/JingMatrix/NeoZygisk/releases) · [Vector](https://github.com/JingMatrix/Vector/releases)
2. Скачай и установи `PixelGravity.apk`. [Релизы Pixel Gravity](https://github.com/gmdbx/pixel-gravity/releases/latest)
3. В **Vector → Модули → Pixel Gravity** включи модуль, отметь **Pixel Launcher** и перезапусти лаунчер. Если пользуешься ADB, выполни `adb shell am force-stop com.google.android.apps.nexuslauncher`, затем вернись на домашний экран.
4. На домашнем экране встряхни телефон, затем наклони его. Чтобы вернуть иконки в сетку, встряхни телефон снова. Короткое нажатие на движущуюся иконку открывает приложение.

Целевая конфигурация — Pixel 6 со штатным Android 17 `CP3A.261005.002.A1`. Если эффект не запускается, проверь область действия модуля в Vector и собери журнал командой `adb logcat -d -s PixelGravity`.

### Сборка

Нужны Java, `zip`, Android SDK Platform 35, Build Tools 35.0.0, Eclipse ECJ 3.40.0, Xposed API 82 и ключ подписи Android. Задай абсолютные пути в переменных `ANDROID_JAR`, `BUILD_TOOLS`, `ECJ_JAR`, `XPOSED_JAR` и `GRAVITY_KEYSTORE`. Запусти `./build.sh`. APK появится в `build/PixelGravity.apk`.

[Исходный твик Gravitation для iOS](https://github.com/kritanta-ios-tweaks/Gravitation)

## English

**A port of the Gravitation tweak for Pixel Launcher.** Shake your phone to let shortcuts, folders, widgets, and the search bar move with its tilt. Each home screen page preserves its own icon positions when you swipe between pages.

### Installation and usage

1. Use the stock Pixel Launcher (`com.google.android.apps.nexuslauncher`) and an Xposed framework that supports the Legacy Xposed API. If KernelSU is already working, install the NeoZygisk and Vector ZIP files through KernelSU Manager and reboot. [NeoZygisk](https://github.com/JingMatrix/NeoZygisk/releases) · [Vector](https://github.com/JingMatrix/Vector/releases)
2. Download and install `PixelGravity.apk`. [Pixel Gravity releases](https://github.com/gmdbx/pixel-gravity/releases/latest)
3. In **Vector → Modules → Pixel Gravity**, enable the module, select **Pixel Launcher**, and restart the launcher. If you use ADB, run `adb shell am force-stop com.google.android.apps.nexuslauncher` and return to the home screen.
4. Shake your phone on the home screen, then tilt it. Shake again to return the icons to the grid. Tap a moving icon to open its app.

The target device is a Pixel 6 running stock Android 17 build `CP3A.261005.002.A1`. If gravity does not start, check the module scope in Vector and collect logs with `adb logcat -d -s PixelGravity`.

### Build

You need Java, `zip`, Android SDK Platform 35, Build Tools 35.0.0, Eclipse ECJ 3.40.0, Xposed API 82, and an Android signing key. Set absolute paths in `ANDROID_JAR`, `BUILD_TOOLS`, `ECJ_JAR`, `XPOSED_JAR`, and `GRAVITY_KEYSTORE`. Run `./build.sh` to produce `build/PixelGravity.apk`.

[Original Gravitation tweak for iOS](https://github.com/kritanta-ios-tweaks/Gravitation)
