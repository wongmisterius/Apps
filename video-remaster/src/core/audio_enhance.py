"""Remasterización de audio: denoise + realce de voz con DeepFilterNet (GPU) y
normalización de loudness con ffmpeg."""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf
import torch


class AudioEnhancer:
    def __init__(self, device: str | None = None):
        from df.enhance import init_df

        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self._model, self._df_state, _ = init_df()
        if self.device == "cuda":
            self._model = self._model.to("cuda")

    def denoise_wav(self, in_wav: Path, out_wav: Path) -> None:
        from df.enhance import enhance, load_audio, save_audio

        audio, _ = load_audio(str(in_wav), sr=self._df_state.sr())
        if self.device == "cuda":
            audio = audio.to("cuda")
        enhanced = enhance(self._model, self._df_state, audio)
        save_audio(str(out_wav), enhanced, self._df_state.sr())

    @staticmethod
    def normalize_loudness(in_wav: Path, out_wav: Path, target_lufs: float = -16.0) -> None:
        """Normaliza el loudness (streaming ~ -16 LUFS) en una pasada."""
        cmd = [
            "ffmpeg", "-y", "-i", str(in_wav), "-af",
            f"loudnorm=I={target_lufs}:TP=-1.5:LRA=11",
            "-ar", "48000", str(out_wav),
        ]
        subprocess.run(cmd, capture_output=True, text=True, check=True)
