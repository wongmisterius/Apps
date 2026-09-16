# Video/Audio Remaster (local, GPU NVIDIA)

App de escritorio para Windows que remasteriza video local usando tu GPU:

- **Video:** upscale y restauración cuadro a cuadro con Real-ESRGAN (CUDA/FP16).
- **Audio:** quita ruido de fondo y realza la voz con DeepFilterNet (GPU),
  y normaliza el loudness con `ffmpeg` (`loudnorm`).

Todo corre 100% local, sin subir nada a internet.

## Requisitos

- Windows 10/11 con GPU **NVIDIA** (funciona con 6 GB de VRAM en adelante;
  con menos VRAM usá `--tile` / el campo "Tile" de la GUI).
- [Python 3.11](https://www.python.org/downloads/) (64 bits).
- Drivers NVIDIA actualizados (CUDA 12.1+).
- [`ffmpeg`](https://www.gyan.dev/ffmpeg/builds/) en el PATH (`ffmpeg -version`
  debe andar desde una consola).

## Instalación

```powershell
cd video-remaster
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# Descarga los pesos de Real-ESRGAN (una sola vez, ~70-130 MB cada uno)
python scripts\download_models.py
```

Si `pip install torch==...+cu121` falla, revisá que tu GPU/driver soporten
CUDA 12.1 o ajustá la versión de `+cu121` en `requirements.txt` según la
tabla de https://pytorch.org/get-started/locally/.

## Uso

### GUI

```powershell
python -m src.app
```

1. Elegí el video de entrada.
2. Elegí modelo, factor de upscale (1x/2x/4x) y si querés denoise/normalize
   de audio.
3. "Remasterizar" y elegí dónde guardar el `.mp4` de salida.

### Línea de comandos

```powershell
python -m src.cli entrada.mp4 salida.mp4 --scale 2 --model realesr-general-x4v3
```

Opciones: `--tile 256` (GPUs con poca VRAM), `--no-denoise`, `--no-normalize`,
`--lufs -14` (target de loudness).

## Cómo funciona (`src/core/`)

- `ffmpeg_utils.py` — extrae audio, decodifica/codifica video en crudo
  (`rawvideo`) vía pipes de `ffmpeg`, y hace el remux final.
- `video_enhance.py` — carga Real-ESRGAN (`basicsr` + `realesrgan`) sobre
  CUDA con FP16 y mejora frame por frame.
- `audio_enhance.py` — DeepFilterNet para denoise y `ffmpeg loudnorm` para
  normalizar volumen.
- `pipeline.py` — orquesta todo: audio en paralelo lógico, video en
  streaming (no vuelca frames a disco como imágenes sueltas), y remux.

## Modelos disponibles

| Nombre                        | Uso                                   |
|--------------------------------|----------------------------------------|
| `realesr-general-x4v3`         | Uso general, rápido (default)          |
| `RealESRGAN_x4plus`            | Más calidad, más lento                 |
| `RealESRGAN_x4plus_anime_6B`   | Optimizado para animación/dibujos      |

## Limitaciones conocidas

- El factor de upscale de video real depende del modelo nativo (4x); pedir
  2x hace 4x y reescala hacia abajo, no es un modelo "nativo 2x".
- No incluye interpolación de fotogramas (suavizado a 60 fps) en el
  pipeline principal; `rife-ncnn-vulkan-python` queda listado en
  `requirements.txt` como paso opcional a integrar si lo necesitás.
- Pensado para clips cortos/medios: un video largo en 4K puede tardar horas
  en GPUs modestas (el cuello de botella es el upscale cuadro a cuadro).
