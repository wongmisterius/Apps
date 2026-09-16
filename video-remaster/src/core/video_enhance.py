"""Upscale/restauración de video cuadro a cuadro con Real-ESRGAN sobre CUDA."""
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import torch

MODELS_DIR = Path(__file__).resolve().parents[2] / "models"

# nombre visible -> (archivo .pth, factor de escala del modelo)
MODEL_CATALOG = {
    "realesr-general-x4v3": ("realesr-general-x4v3.pth", 4),   # rápido, uso general
    "RealESRGAN_x4plus": ("RealESRGAN_x4plus.pth", 4),          # más calidad, más lento
    "RealESRGAN_x4plus_anime_6B": ("RealESRGAN_x4plus_anime_6B.pth", 4),  # animación
}


class VideoUpscaler:
    def __init__(self, model_name: str = "realesr-general-x4v3", scale: int = 2,
                 tile: int = 0, device: Optional[str] = None):
        if model_name not in MODEL_CATALOG:
            raise ValueError(f"Modelo desconocido: {model_name}. Opciones: {list(MODEL_CATALOG)}")

        from basicsr.archs.rrdbnet_arch import RRDBNet
        from realesrgan import RealESRGANer
        from realesrgan.archs.srvgg_arch import SRVGGNetCompact

        weight_file, native_scale = MODEL_CATALOG[model_name]
        weight_path = MODELS_DIR / weight_file
        if not weight_path.exists():
            raise FileNotFoundError(
                f"Falta el modelo {weight_path}. Corré scripts/download_models.py primero."
            )

        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        if self.device == "cpu":
            raise RuntimeError(
                "No se detectó GPU CUDA. Esta app está pensada para remasterizar con GPU; "
                "instalá los drivers NVIDIA y el PyTorch con soporte CUDA (ver README)."
            )

        if model_name == "realesr-general-x4v3":
            # Este checkpoint es una red compacta (SRVGGNetCompact), no RRDBNet.
            arch = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64,
                                    num_conv=32, upscale=native_scale, act_type="prelu")
        else:
            arch = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23,
                            num_grow_ch=32, scale=native_scale)

        self._upsampler = RealESRGANer(
            scale=native_scale,
            model_path=str(weight_path),
            model=arch,
            tile=tile,             # >0 para GPUs con poca VRAM (ej. 256/512)
            tile_pad=10,
            pre_pad=0,
            half=True,             # FP16, casi el doble de rápido en GPUs NVIDIA
            device=self.device,
        )
        # factor final que pide el usuario (puede ser < factor nativo del modelo)
        self.target_scale = scale
        self.native_scale = native_scale

    def enhance_frame(self, frame_rgb: np.ndarray) -> np.ndarray:
        output, _ = self._upsampler.enhance(frame_rgb, outscale=self.target_scale)
        return output

    def enhance_video_frames(
        self,
        frame_iter,
        on_progress: Optional[Callable[[int, int], None]] = None,
        total_frames: Optional[int] = None,
    ):
        """Recibe un iterable de frames rgb24 (np.ndarray HxWx3) y va emitiendo los mejorados."""
        for idx, frame in enumerate(frame_iter, start=1):
            yield self.enhance_frame(frame)
            if on_progress:
                on_progress(idx, total_frames or 0)
