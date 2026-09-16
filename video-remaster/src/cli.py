"""Uso: python -m src.cli entrada.mp4 salida.mp4 [--scale 2] [--model realesr-general-x4v3]
       [--no-denoise] [--no-normalize] [--tile 256]"""
import argparse
import sys
from pathlib import Path

from .core.pipeline import RemasterOptions, remaster


def main() -> int:
    p = argparse.ArgumentParser(description="Remasteriza video + audio localmente usando GPU.")
    p.add_argument("input", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--scale", type=int, default=2, help="Factor de upscale de video (1, 2 o 4)")
    p.add_argument("--model", default="realesr-general-x4v3")
    p.add_argument("--tile", type=int, default=0, help="Tamaño de tile para GPUs con poca VRAM")
    p.add_argument("--no-denoise", action="store_true")
    p.add_argument("--no-normalize", action="store_true")
    p.add_argument("--lufs", type=float, default=-16.0)
    args = p.parse_args()

    opts = RemasterOptions(
        upscale_factor=args.scale,
        model_name=args.model,
        tile=args.tile,
        denoise_audio=not args.no_denoise,
        normalize_audio=not args.no_normalize,
        target_lufs=args.lufs,
    )

    def on_progress(stage: str, i: int, total: int) -> None:
        if total > 1:
            pct = 100 * i / total
            print(f"\r[{stage}] {i}/{total} ({pct:5.1f}%)", end="", flush=True)
        else:
            print(f"[{stage}]")

    remaster(args.input, args.output, opts, on_progress=on_progress)
    print(f"\nListo -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
