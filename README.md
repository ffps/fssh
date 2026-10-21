# fSSH

Минимальный SSH/SCP/SFTP-сервер для Android 4.2+ (API 17). Один пользователь, вход по паролю
(имя пользователя — любое), для администрирования устройства.

Внутри — [Dropbear](https://matt.ucc.asn.au/dropbear/) (SSH + `scp`) и `sftp-server` из OpenSSH,
собранные NDK под armeabi-v7a, arm64-v8a, x86, x86_64. Java-часть — только окно настроек и
foreground-сервис (без AndroidX).

* Порт (по умолчанию 8022), пароль (пустой → сервер не стартует), домашний каталог (по умолчанию «Загрузки»),
  автозапуск при загрузке (без окна).
* Пока сервер работает, в уведомлениях есть пункт; нажатие открывает окно настроек.
* Работает `ssh`, `scp` (в т.ч. `scp -O`) и `sftp`/WinSCP/файловые менеджеры. Вход по ключу тоже возможен
  (`authorized_keys` — в каталоге `files/ssh` приложения).
* Сессии выполняются от uid приложения (не root). Доступ к `/sdcard`: разрешение на файлы в
  настройках Android («Доступ ко всем файлам» для Android 11+).

## Сборка релиза

```
git tag v1.2.3 && git push origin v1.2.3
```

Workflow `.github/workflows/release.yml` соберёт `fSSH-1.2.3.apk` и приложит его к GitHub Release.

Подпись: добавьте секреты репозитория `KEYSTORE_BASE64` (`base64 -w0 release.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, при необходимости `KEY_PASSWORD`. Без них APK подписывается
временным ключом — такой APK придётся удалить перед установкой следующей версии.

Локально: `native/build.sh <NDK r23c> app/src/main/jniLibs`, затем `gradle :app:assembleRelease`.
NDK r23c — последний, поддерживающий API 17.

## 

## Иконка

`icons/fssh.svg` — исходник; PNG для приложения получены из него (`res/drawable-*dpi/ic_launcher.png`, `ic_stat.png`), для Android 8+ — adaptive-иконка.

## Лицензии

Dropbear — MIT-подобная лицензия, OpenSSH — BSD. Патчи: `native/patches/`.
