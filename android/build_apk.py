"""Сборка APK напрямую инструментами Android SDK, без Gradle.

Gradle здесь не нужен: приложение использует только системные классы Android
(никаких AndroidX и сторонних библиотек), поэтому сборка полностью локальная и
не требует ничего скачивать.

Порядок шагов:

  1. aapt2 compile — скомпилировать ресурсы (строки, тему, иконку);
  2. aapt2 link    — собрать пакет ресурсов и сгенерировать R.java;
  3. javac         — собрать классы (UTF-8 обязателен: в строках русский текст);
  4. d8            — превратить классы в classes.dex;
  5. добавить dex  — вписать classes.dex в пакет;
  6. zipalign      — выровнять пакет (требование Android);
  7. apksigner     — подписать ключом (неподписанный APK не устанавливается).

Запуск:  .venv\\Scripts\\python.exe android\\build_apk.py [--install]
"""

from __future__ import annotations

import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE / "app"
BUILD = HERE / "build"
RESULT = HERE.parent / "VoiceBot-demo.apk"

# Ключ подписи лежит рядом со сборочной папкой и НЕ удаляется при пересборке:
# иначе каждая сборка получала бы новую подпись и установка поверх старой версии
# падала бы с «signatures do not match».
KEYSTORE = HERE / "debug.keystore"
STORE_PASS = "android"
KEY_ALIAS = "androiddebugkey"
MIN_SDK = "24"
TARGET_SDK = "35"
APP_LABEL = "VoiceBot"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


# ---------------------------------------------------------------------------
# Окружение
# ---------------------------------------------------------------------------
def sdk_root() -> Path:
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "AppData" / "Local" / "Android" / "Sdk"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_dir():
            return Path(candidate)
    raise SystemExit("Не найден Android SDK. Задайте переменную ANDROID_HOME.")


def version_key(path: Path) -> list[int]:
    return [int(part) for part in re.findall(r"\d+", path.name)] or [0]


def newest(directory: Path) -> Path:
    items = [item for item in directory.iterdir() if item.is_dir()]
    if not items:
        raise SystemExit(f"Пусто: {directory}")
    return max(items, key=version_key)


def java_home() -> Path:
    value = os.environ.get("JAVA_HOME")
    if value and Path(value).is_dir():
        return Path(value)
    javac = shutil.which("javac")
    if javac:
        return Path(javac).resolve().parent.parent
    raise SystemExit("Не найден JDK. Задайте переменную JAVA_HOME.")


def run(args: list, env: dict, cwd: Path | None = None) -> None:
    """Запустить инструмент. .bat-файлы Windows требуют shell=True."""
    args = [str(arg) for arg in args]
    command = subprocess.list2cmdline(args)
    if args[0].lower().endswith((".bat", ".cmd")):
        result = subprocess.run(command, shell=True, cwd=cwd, env=env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
    else:
        result = subprocess.run(args, cwd=cwd, env=env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(f"Шаг не удался: {command}")


def tools() -> tuple[dict, dict]:
    """Найти все нужные инструменты и подготовить окружение."""
    sdk = sdk_root()
    build_tools = newest(sdk / "build-tools")
    platform = newest(sdk / "platforms")
    android_jar = platform / "android.jar"
    if not android_jar.is_file():
        raise SystemExit(f"Нет android.jar: {android_jar}")

    home = java_home()
    env = dict(os.environ)
    env["JAVA_HOME"] = str(home)
    env["PATH"] = str(home / "bin") + os.pathsep + env.get("PATH", "")

    found = {
        "sdk": sdk,
        "aapt2": build_tools / "aapt2.exe",
        "d8": build_tools / "d8.bat",
        "zipalign": build_tools / "zipalign.exe",
        "apksigner": build_tools / "apksigner.bat",
        "javac": home / "bin" / "javac.exe",
        "keytool": home / "bin" / "keytool.exe",
        "adb": sdk / "platform-tools" / "adb.exe",
        "android_jar": android_jar,
        "build_tools": build_tools,
        "platform": platform,
    }
    missing = [name for name in ("aapt2", "d8", "zipalign", "apksigner", "javac", "keytool")
               if not Path(found[name]).is_file()]
    if missing:
        raise SystemExit("Не найдены инструменты: " + ", ".join(missing))
    return found, env


# ---------------------------------------------------------------------------
# Иконка
# ---------------------------------------------------------------------------
def make_icon(size: int = 192) -> Path:
    """Нарисовать иконку (скруглённый квадрат с микрофоном) без внешних библиотек."""
    accent = (37, 99, 235)
    dark = (17, 24, 39)
    white = (255, 255, 255)
    radius = size * 0.22

    rows: list[bytes] = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            dx = min(x, size - 1 - x)
            dy = min(y, size - 1 - y)
            inside = True
            if dx < radius and dy < radius:
                inset = radius - ((radius - dx) ** 2 + (radius - dy) ** 2) ** 0.5
                inside = inset <= 0.5

            color = dark
            if inside:
                cx, cy = size / 2, size * 0.44
                half_w, half_h = size * 0.15, size * 0.21
                if abs(x - cx) <= half_w and abs(y - cy) <= half_h:
                    color = white
                else:
                    ring = (x - cx) ** 2 + (y - size * 0.5) ** 2
                    if (size * 0.28) ** 2 <= ring <= (size * 0.34) ** 2 and y >= size * 0.5:
                        color = white
                    elif abs(x - cx) <= size * 0.035 and size * 0.6 <= y <= size * 0.74:
                        color = white
                    elif abs(y - size * 0.74) <= size * 0.035 and abs(x - cx) <= size * 0.16:
                        color = white
                    elif x + y < size * 1.1:
                        color = accent
            row += bytes((*color, 255))
        rows.append(bytes(row))

    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )

    target = APP / "res" / "mipmap-xxxhdpi"
    target.mkdir(parents=True, exist_ok=True)
    icon = target / "ic_launcher.png"
    icon.write_bytes(png)
    print(f"  иконка: {icon.relative_to(HERE)} ({len(png)} байт)")
    return icon


# ---------------------------------------------------------------------------
# Сборка
# ---------------------------------------------------------------------------
def sources() -> list[Path]:
    return sorted((APP / "src").rglob("*.java")) + sorted((BUILD / "gen").rglob("*.java"))


def java_classes() -> list[Path]:
    return sorted((BUILD / "classes").rglob("*.class"))


def add_dex(apk: Path, dex: Path, target: Path) -> None:
    """Вписать classes.dex в пакет: aapt2 его не создаёт."""
    with zipfile.ZipFile(apk) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as output:
        for item in source.infolist():
            output.writestr(item, source.read(item.filename))
        output.write(dex, "classes.dex")


def build(install: bool = False) -> Path:
    found, env = tools()
    aapt2 = found["aapt2"]
    android_jar = found["android_jar"]
    adb = found["adb"] if Path(found["adb"]).is_file() else None

    print(f"SDK:          {found['sdk']}")
    print(f"build-tools:  {found['build_tools'].name}")
    print(f"platform:     {found['platform'].name}")

    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)

    print("1/7 ресурсы…")
    make_icon()
    res_zip = BUILD / "res.zip"
    run([aapt2, "compile", "--dir", "res", "-o", res_zip], env, cwd=APP)

    print("2/7 упаковка ресурсов…")
    unsigned = BUILD / "app.apk"
    run([aapt2, "link", "-o", unsigned,
         "-I", android_jar,
         "--manifest", "AndroidManifest.xml",
         "-R", res_zip,
         "--auto-add-overlay",
         "--java", BUILD / "gen",
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", "1",
         "--version-name", "1.0"], env, cwd=APP)

    print("3/7 компиляция классов…")
    classpath = BUILD / "classes"
    classpath.mkdir()
    run([found["javac"],
         "-encoding", "UTF-8", "-nowarn", "--release", "17",
         "-classpath", android_jar, "-d", classpath, *sources()], env)

    print("4/7 сборка classes.dex…")
    dex_dir = BUILD / "dex"
    dex_dir.mkdir()
    run([found["d8"], "--release", "--min-api", MIN_SDK, "--lib", android_jar,
         "--output", dex_dir, *java_classes()], env)

    print("5/7 добавление dex в пакет…")
    with_dex = BUILD / "app-dex.apk"
    add_dex(unsigned, dex_dir / "classes.dex", with_dex)

    print("6/7 выравнивание…")
    aligned = BUILD / "app-aligned.apk"
    run([found["zipalign"], "-f", "-p", "4", with_dex, aligned], env)

    print("7/7 подпись…")
    if not KEYSTORE.is_file():
        run([found["keytool"],
             "-genkeypair", "-v",
             "-keystore", KEYSTORE,
             "-storepass", STORE_PASS,
             "-keypass", STORE_PASS,
             "-alias", KEY_ALIAS,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"], env)
    run([found["apksigner"], "sign",
         "--ks", KEYSTORE,
         "--ks-pass", f"pass:{STORE_PASS}",
         "--key-pass", f"pass:{STORE_PASS}",
         "--out", RESULT, aligned], env)
    run([found["apksigner"], "verify", "--print-certs", RESULT], env)

    print(f"\nГотово: {RESULT} ({RESULT.stat().st_size / 1024:.0f} КБ)")

    if install and adb:
        print("Устанавливаю на подключённое устройство…")
        run([adb, "install", "-r", RESULT], env)
        run([adb, "shell", "am", "start", "-n", "com.forkly.voicebot/.MainActivity"], env)
    return RESULT


if __name__ == "__main__":
    try:
        build(install="--install" in sys.argv)
    except SystemExit as exc:
        print(f"\nОшибка: {exc}")
        raise
