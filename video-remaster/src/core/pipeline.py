"""Orquesta el remaster completo: audio (denoise + loudness) + video (upscale GPU) + remux."""
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import numpy as np

from . import ffmpeg_utils as ff
from .audio_enhance import AudioEnhancer
from .video_enhance import VideoUpscaler

ProgressCB = Callable[[str, int, int], None]  # (etapa, actual, total)


@dataclass
class RemasterOptions:
    upscale_factor: int = 2
    model_name: str = "realesr-general-x4v3"
    tile: int = 0                 # subir (ej. 256) si falta VRAM
    denoise_audio: bool = True
    normalize_audio: bool = True
    target_lufs: float = -16.0


def _read_raw_frames(proc: subprocess.Popen, width: int, height: int):
    frame_bytes = width * height * 3
    while True:
        buf = proc.stdout.read(frame_bytes)
        if len(buf) < frame_bytes:
            break
        yield np.frombuffer(buf, dtype=np.uint8).reshape(height, width, 3)


def remaster(
    input_path: Path,
    output_path: Path,
    opts: RemasterOptions,
    on_progress: Optional[ProgressCB] = None,
) -> None:
    input_path = Path(input_path)
    output_path = Path(output_path)
    info = ff.probe(input_path)
    total_frames = int(info.duration * info.fps) if info.duration else 0

    with tempfile.TemporaryDirectory(prefix="remaster_") as tmp:
        tmp = Path(tmp)
        raw_audio = tmp / "audio_raw.wav"
        denoised_audio = tmp / "audio_denoised.wav"
        final_audio = tmp / "audio_final.wav"
        video_no_audio = tmp / "video_enhanced.mp4"

        # --- Audio ---
        if info.has_audio:
            if on_progress:
                on_progress("audio: extrayendo", 0, 1)
            ff.extract_audio(input_path, raw_audio)

            audio_src = raw_audio
            if opts.denoise_audio:
                if on_progress:
                    on_progress("audio: denoise (GPU)", 0, 1)
                enhancer = AudioEnhancer()
                enhancer.denoise_wav(raw_audio, denoised_audio)
                audio_src = denoised_audio

            if opts.normalize_audio:
                if on_progress:
                    on_progress("audio: normalizando loudness", 0, 1)
                AudioEnhancer.normalize_loudness(audio_src, final_audio, opts.target_lufs)
            else:
                final_audio = audio_src

        # --- Video ---
        if on_progress:
            on_progress("video: cargando modelo (GPU)", 0, 1)
        upscaler = VideoUpscaler(
            model_name=opts.model_name, scale=opts.upscale_factor, tile=opts.tile,
        )

        reader = subprocess.Popen(
            ff.raw_frame_reader_cmd(input_path),
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        out_w, out_h = info.width * opts.upscale_factor, info.height * opts.upscale_factor
        writer = subprocess.Popen(
            ff.raw_frame_writer_cmd(video_no_audio, out_w, out_h, info.fps),
            stdin=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )

        frames = _read_raw_frames(reader, info.width, info.height)

        def _progress(i, total):
            if on_progress:
                on_progress("video: mejorando frames (GPU)", i, total or total_frames)

        for enhanced in upscaler.enhance_video_frames(frames, on_progress=_progress, total_frames=total_frames):
            writer.stdin.write(enhanced.tobytes())

        writer.stdin.close()
        writer.wait()
        reader.wait()

        # --- Mux final ---
        if on_progress:
            on_progress("finalizando: uniendo audio y video", 0, 1)
        if info.has_audio:
            ff.mux(video_no_audio, final_audio, output_path)
        else:
            shutil.move(str(video_no_audio), str(output_path))

    if on_progress:
        on_progress("listo", 1, 1)
