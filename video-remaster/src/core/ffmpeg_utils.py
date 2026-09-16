"""Wrappers de subprocess sobre ffmpeg/ffprobe. Requiere ffmpeg en el PATH."""
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path


class FFmpegError(RuntimeError):
    pass


@dataclass
class VideoInfo:
    width: int
    height: int
    fps: float
    duration: float
    has_audio: bool
    pix_fmt: str


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise FFmpegError(f"{' '.join(cmd)}\n{proc.stderr}")
    return proc


def probe(input_path: Path) -> VideoInfo:
    cmd = [
        "ffprobe", "-v", "error", "-print_format", "json",
        "-show_streams", "-show_format", str(input_path),
    ]
    proc = _run(cmd)
    data = json.loads(proc.stdout)
    v_stream = next(s for s in data["streams"] if s["codec_type"] == "video")
    a_stream = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)

    num, den = v_stream.get("r_frame_rate", "25/1").split("/")
    fps = float(num) / float(den) if float(den) != 0 else float(num)

    return VideoInfo(
        width=int(v_stream["width"]),
        height=int(v_stream["height"]),
        fps=fps,
        duration=float(data["format"].get("duration", 0.0)),
        has_audio=a_stream is not None,
        pix_fmt=v_stream.get("pix_fmt", "yuv420p"),
    )


def extract_audio(input_path: Path, out_wav: Path, sample_rate: int = 48000) -> None:
    cmd = [
        "ffmpeg", "-y", "-i", str(input_path),
        "-vn", "-acodec", "pcm_s16le", "-ar", str(sample_rate), "-ac", "2",
        str(out_wav),
    ]
    _run(cmd)


def raw_frame_reader_cmd(input_path: Path) -> list[str]:
    """Comando ffmpeg que vuelca los frames decodificados como rawvideo rgb24 por stdout."""
    return [
        "ffmpeg", "-y", "-i", str(input_path),
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
    ]


def raw_frame_writer_cmd(out_video: Path, width: int, height: int, fps: float) -> list[str]:
    """Comando ffmpeg que arma un video a partir de frames rgb24 crudos por stdin."""
    return [
        "ffmpeg", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{width}x{height}", "-r", str(fps),
        "-i", "-",
        "-c:v", "libx264", "-preset", "medium", "-crf", "16",
        "-pix_fmt", "yuv420p",
        str(out_video),
    ]


def mux(video_path: Path, audio_path: Path, out_path: Path) -> None:
    cmd = [
        "ffmpeg", "-y",
        "-i", str(video_path), "-i", str(audio_path),
        "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
        "-shortest",
        str(out_path),
    ]
    _run(cmd)
