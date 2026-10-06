import os
import shutil
import tempfile
import subprocess
import time
import wave
from queue import Empty, Queue
from pathlib import Path

import keyboard
import pyperclip
from faster_whisper import WhisperModel


CUDA_DLLS = ("cublas64_12.dll", "cublasLt64_12.dll", "cudart64_12.dll")
if not all(shutil.which(name) for name in CUDA_DLLS):
    # Reuse the CUDA 12 runtime already installed with Ollama on this PC.
    cuda_dll_dir = Path(os.environ.get(
        "VOICE_BRIDGE_CUDA_DLL_DIR",
        str(Path(os.environ["LOCALAPPDATA"]) / "Programs/Ollama/lib/ollama/cuda_v12"),
    ))
    if not all((cuda_dll_dir / name).is_file() for name in CUDA_DLLS):
        raise RuntimeError(
            "CUDA 12 の実行用 DLL が見つかりません。"
            "VOICE_BRIDGE_CUDA_DLL_DIR に DLL のあるフォルダーを指定してください。"
        )
    os.environ["PATH"] = str(cuda_dll_dir) + os.pathsep + os.environ["PATH"]

ADB = os.environ.get("VOICE_BRIDGE_ADB") or shutil.which("adb") or "adb"
USB_SERIAL = os.environ.get("VOICE_BRIDGE_USB_SERIAL")
WIFI_ENDPOINT = os.environ.get("VOICE_BRIDGE_WIFI_ENDPOINT")
SERIAL = None
PACKAGE = "dev.syumai.butler"

WAV_PATH = Path(os.environ.get("VOICE_BRIDGE_WAV_PATH", Path(tempfile.gettempdir()) / "voice_bridge.wav"))
REMOTE_PCM = "cache/voice_input.pcm"

VOICE_START = "dev.syumai.butler.VOICE_START"
VOICE_STOP = "dev.syumai.butler.VOICE_STOP"
VOICE_IDLE = "dev.syumai.butler.VOICE_IDLE"


def device_ready(serial):
    if not serial:
        return False
    try:
        result = subprocess.run(
            [ADB, "-s", serial, "get-state"],
            capture_output=True, text=True, timeout=3,
        )
        return result.returncode == 0 and result.stdout.strip() == "device"
    except subprocess.TimeoutExpired:
        return False


def ensure_device():
    global SERIAL
    if WIFI_ENDPOINT and not device_ready(WIFI_ENDPOINT):
        try:
            subprocess.run(
                [ADB, "connect", WIFI_ENDPOINT],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5,
            )
        except subprocess.TimeoutExpired:
            pass
    if device_ready(WIFI_ENDPOINT):
        SERIAL = WIFI_ENDPOINT
    elif device_ready(USB_SERIAL):
        SERIAL = USB_SERIAL
    else:
        SERIAL = None
        raise ConnectionError("Echo ShowへのADB接続を待機中")
    return SERIAL


def run_device(*args, capture=False):
    global SERIAL
    if SERIAL is None:
        ensure_device()
    for attempt in range(2):
        try:
            return subprocess.run(
                [ADB, "-s", SERIAL, *args],
                stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                stderr=subprocess.PIPE if capture else subprocess.DEVNULL,
                check=True, timeout=15,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if attempt:
                raise
            ensure_device()


def broadcast(action):
    run_device("shell", "am", "broadcast", "-a", action, "-p", PACKAGE)


def delete_old_pcm():
    run_device("shell", "run-as", PACKAGE, "rm", "-f", REMOTE_PCM)


def get_pcm():
    return run_device("exec-out", "run-as", PACKAGE, "cat", REMOTE_PCM, capture=True).stdout


print("Whisperモデル読み込み中...")
model_load_start = time.perf_counter()
model = WhisperModel("small", device="cuda", compute_type="float16")
print(f"Whisperモデル読み込み: {time.perf_counter() - model_load_start:.2f}秒 (small / CUDA / float16)")

print()
try:
    print(f"ADB接続: {ensure_device()}")
except ConnectionError as error:
    print(error)
print("準備完了")
print("F8を押している間だけ録音 / 離すと文字起こし / ESCで終了")

recording = False
key_events = Queue()
keyboard.hook(key_events.put)

while True:
    try:
        event = key_events.get(timeout=10)
    except Empty:
        if SERIAL is None:
            try:
                print(f"ADB接続: {ensure_device()}")
            except ConnectionError:
                pass
        continue

    if event.name == "esc" and event.event_type == keyboard.KEY_DOWN:
        if recording:
            try:
                broadcast(VOICE_STOP)
                broadcast(VOICE_IDLE)
            except (ConnectionError, subprocess.SubprocessError):
                pass
        print("終了")
        break

    if event.name != "f8":
        continue

    if event.event_type == keyboard.KEY_DOWN:
        if recording:
            continue

        try:
            ensure_device()
            delete_old_pcm()
            broadcast(VOICE_START)
        except (ConnectionError, subprocess.SubprocessError) as error:
            print(f"ADB接続失敗: {error}")
            continue
        recording = True

        print()
        print("LISTENING...")

    elif event.event_type == keyboard.KEY_UP:
        if not recording:
            continue

        recording = False
        try:
            broadcast(VOICE_STOP)
        except (ConnectionError, subprocess.SubprocessError) as error:
            print(f"録音停止に失敗: {error}")
            try:
                broadcast(VOICE_IDLE)
            except (ConnectionError, subprocess.SubprocessError):
                pass
            continue

        print("TRANSCRIBING...")

        try:
            # Echo Show側でファイルを閉じるまで少し待つ
            time.sleep(0.5)

            try:
                pcm = get_pcm()
            except (ConnectionError, subprocess.SubprocessError):
                print("録音ファイルを取得できませんでした")
                continue

            print(f"録音取得: {len(pcm)} bytes")

            if len(pcm) < 1000:
                print("録音が短すぎます")
                continue

            with wave.open(str(WAV_PATH), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                wav.writeframes(pcm)

            transcribe_start = time.perf_counter()
            segments, info = model.transcribe(
                str(WAV_PATH),
                language="ja",
                vad_filter=False
            )

            text = "".join(segment.text for segment in segments).strip()
            print(f"文字起こし: {time.perf_counter() - transcribe_start:.2f}秒")

            print()
            print("認識結果:")
            print(text if text else "(認識なし)")

            if text:
                pyperclip.copy(text)
                time.sleep(0.1)
                keyboard.press_and_release("ctrl+v")
                print("→ 入力欄へ貼り付けました")
        finally:
            try:
                broadcast(VOICE_IDLE)
            except (ConnectionError, subprocess.SubprocessError) as error:
                print(f"IDLE通知に失敗: {error}")
            print()
            print("IDLE - F8を押して話してください")
