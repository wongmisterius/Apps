"""Descarga los pesos de Real-ESRGAN a video-remaster/models/ (no se versionan en git)."""
import sys
import urllib.request
from pathlib import Path

MODELS_DIR = Path(__file__).resolve().parents[1] / "models"

URLS = {
    "realesr-general-x4v3.pth":
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth",
    "RealESRGAN_x4plus.pth":
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
    "RealESRGAN_x4plus_anime_6B.pth":
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth",
}


def download(name: str, url: str) -> None:
    dest = MODELS_DIR / name
    if dest.exists():
        print(f"ya existe: {dest}")
        return
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"descargando {name}...")
    urllib.request.urlretrieve(url, dest)
    print(f"ok -> {dest}")


def main() -> int:
    wanted = sys.argv[1:] or list(URLS.keys())
    for name in wanted:
        if name not in URLS:
            print(f"modelo desconocido: {name} (opciones: {list(URLS)})")
            continue
        download(name, URLS[name])
    return 0


if __name__ == "__main__":
    sys.exit(main())
