"""GUI de escritorio (PySide6) para remasterizar video + audio con GPU local."""
import sys
import traceback
from pathlib import Path

from PySide6.QtCore import QThread, Signal
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFileDialog, QGridLayout, QLabel,
    QMainWindow, QMessageBox, QProgressBar, QPushButton, QSpinBox, QTextEdit,
    QVBoxLayout, QWidget,
)

from .core.pipeline import RemasterOptions, remaster
from .core.video_enhance import MODEL_CATALOG


class RemasterWorker(QThread):
    progress = Signal(str, int, int)
    finished_ok = Signal(str)
    failed = Signal(str)

    def __init__(self, input_path: Path, output_path: Path, opts: RemasterOptions):
        super().__init__()
        self.input_path = input_path
        self.output_path = output_path
        self.opts = opts

    def run(self) -> None:
        try:
            remaster(
                self.input_path, self.output_path, self.opts,
                on_progress=lambda stage, i, t: self.progress.emit(stage, i, t),
            )
            self.finished_ok.emit(str(self.output_path))
        except Exception:
            self.failed.emit(traceback.format_exc())


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Remaster Video/Audio local (GPU)")
        self.resize(640, 480)

        self.input_path: Path | None = None
        self.worker: RemasterWorker | None = None

        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)

        self.input_label = QLabel("Sin archivo seleccionado")
        btn_pick = QPushButton("Elegir video de entrada...")
        btn_pick.clicked.connect(self.pick_input)
        layout.addWidget(btn_pick)
        layout.addWidget(self.input_label)

        form = QGridLayout()
        row = 0

        form.addWidget(QLabel("Modelo de video:"), row, 0)
        self.model_combo = QComboBox()
        self.model_combo.addItems(list(MODEL_CATALOG.keys()))
        form.addWidget(self.model_combo, row, 1)
        row += 1

        form.addWidget(QLabel("Factor de upscale:"), row, 0)
        self.scale_spin = QSpinBox()
        self.scale_spin.setRange(1, 4)
        self.scale_spin.setValue(2)
        form.addWidget(self.scale_spin, row, 1)
        row += 1

        form.addWidget(QLabel("Tile (0 = auto, subir si falta VRAM):"), row, 0)
        self.tile_spin = QSpinBox()
        self.tile_spin.setRange(0, 1024)
        self.tile_spin.setSingleStep(64)
        form.addWidget(self.tile_spin, row, 1)
        row += 1

        self.denoise_check = QCheckBox("Quitar ruido de audio (GPU)")
        self.denoise_check.setChecked(True)
        form.addWidget(self.denoise_check, row, 0, 1, 2)
        row += 1

        self.normalize_check = QCheckBox("Normalizar loudness")
        self.normalize_check.setChecked(True)
        form.addWidget(self.normalize_check, row, 0, 1, 2)
        row += 1

        layout.addLayout(form)

        self.start_btn = QPushButton("Remasterizar")
        self.start_btn.clicked.connect(self.start_remaster)
        self.start_btn.setEnabled(False)
        layout.addWidget(self.start_btn)

        self.progress_bar = QProgressBar()
        layout.addWidget(self.progress_bar)

        self.log = QTextEdit()
        self.log.setReadOnly(True)
        layout.addWidget(self.log)

    def pick_input(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Elegir video", "", "Video (*.mp4 *.mkv *.mov *.avi *.webm)"
        )
        if path:
            self.input_path = Path(path)
            self.input_label.setText(str(self.input_path))
            self.start_btn.setEnabled(True)

    def start_remaster(self) -> None:
        if not self.input_path:
            return
        out_path, _ = QFileDialog.getSaveFileName(
            self, "Guardar como", str(self.input_path.with_name(
                self.input_path.stem + "_remaster.mp4")), "MP4 (*.mp4)"
        )
        if not out_path:
            return

        opts = RemasterOptions(
            upscale_factor=self.scale_spin.value(),
            model_name=self.model_combo.currentText(),
            tile=self.tile_spin.value(),
            denoise_audio=self.denoise_check.isChecked(),
            normalize_audio=self.normalize_check.isChecked(),
        )

        self.start_btn.setEnabled(False)
        self.progress_bar.setValue(0)
        self.log.append(f"Iniciando remaster de {self.input_path.name}...")

        self.worker = RemasterWorker(self.input_path, Path(out_path), opts)
        self.worker.progress.connect(self.on_progress)
        self.worker.finished_ok.connect(self.on_finished)
        self.worker.failed.connect(self.on_failed)
        self.worker.start()

    def on_progress(self, stage: str, i: int, total: int) -> None:
        if total > 1:
            self.progress_bar.setMaximum(total)
            self.progress_bar.setValue(i)
        self.log.append(f"[{stage}] {i}/{total}" if total > 1 else f"[{stage}]")

    def on_finished(self, out_path: str) -> None:
        self.log.append(f"Listo: {out_path}")
        self.start_btn.setEnabled(True)
        QMessageBox.information(self, "Remaster completo", f"Video guardado en:\n{out_path}")

    def on_failed(self, trace: str) -> None:
        self.log.append(f"ERROR:\n{trace}")
        self.start_btn.setEnabled(True)
        QMessageBox.critical(self, "Error durante el remaster", trace[-1000:])


def main() -> None:
    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
