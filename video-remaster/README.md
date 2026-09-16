# Video/Audio Remaster (local, GPU NVIDIA)

App de escritorio para Windows que remasteriza video local usando tu GPU:

- **Video:** upscale y restauración cuadro a cuadro con Real-ESRGAN (CUDA/FP16).
- **Audio:** quita ruido de fondo y realza la voz con DeepFilterNet (GPU),
  y normaliza el loudness con `ffmpeg` (`loudnorm`).

Todo corre 100% local, sin subir nada a internet.

## Requisitos

- Windows 10/11 con GPU **NVIDIA** (funciona con 6 GB de VRAM en adelante;
  con menos VRAM usá `--tile` / el campo "Tile" de la GUI).
- Python 3.11 o 3.12 (64 bits) — **no uses 3.13+**, todavía no hay wheels de
  PyTorch/basicsr para versiones tan nuevas y termina compilando todo desde
  cero (o directamente fallando). Instalalo con `py install 3.12` si no lo
  tenés.
- Drivers NVIDIA actualizados (CUDA 12.1+).
- [`ffmpeg`](https://www.gyan.dev/ffmpeg/builds/) en el PATH (`ffmpeg -version`
  debe andar desde una consola).
- [Rust/Cargo](https://rustup.rs/) — `deepfilternet` compila su núcleo en
  Rust y no siempre hay wheel prearmada para tu versión de Python; sin
  Rust instalado la instalación de `requirements.txt` falla.

## Instalación

```powershell
cd video-remaster
python -m venv .venv
.venv\Scripts\activate

# 1) Instalar PyTorch con CUDA PRIMERO (versión exacta según tu driver).
#    Fijate qué CUDA soporta tu driver con: nvidia-smi (esquina sup. derecha)
#    y usá el comando que te arme https://pytorch.org/get-started/locally/
#    (Stable / Windows / Pip / Python / CUDA 12.x). Ejemplo típico hoy:
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121

# 2) Resto de dependencias
pip install -r requirements.txt

# 3) Descarga los pesos de Real-ESRGAN (una sola vez, ~70-130 MB cada uno)
python scripts\download_models.py
```

Si el paso 2 falla con `ModuleNotFoundError: No module named 'torchvision.transforms.functional_tensor'`
al correr la app (no durante la instalación), es un bug conocido de
`basicsr` 1.4.2 con versiones nuevas de torchvision: abrí
`.venv\Lib\site-packages\basicsr\data\degradations.py` y cambiá el import
de `torchvision.transforms.functional_tensor` a
`torchvision.transforms.functional` (la función `rgb_to_grayscale` se
movió ahí).

Verificá que Torch ve la GPU antes de seguir:

```powershell
python -c "import torch; print(torch.__version__, torch.cuda.is_available(), torch.cuda.get_device_name(0))"
```

Si `torch.cuda.is_available()` da `False`, el problema es la combinación
driver/CUDA/torch, no el resto de la app — no sigas con la instalación
hasta que esto de `True`.

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
