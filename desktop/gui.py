"""Interfaz de escritorio simple en español; ejecuta el motor en un proceso separado."""
from __future__ import annotations
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from mivoz import DATA, VARIANTS


class Application(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Mi Voz Local — Qwen en tu PC")
        self.geometry("850x760")
        self.process = None
        self.messages = queue.Queue()
        self.variant = tk.StringVar(value="1.7b-q8_0")
        self.audio = tk.StringVar()
        self.threads = tk.StringVar(value="4")
        self.takes = tk.StringVar(value="1")
        self.status = tk.StringVar(value="Español · CPU · datos guardados en tu PC")
        frame = ttk.Frame(self, padding=16)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Mi Voz Local", font=("Segoe UI", 20, "bold")).pack(anchor="w")
        ttk.Label(frame, text="Texto a voz con una referencia autorizada. El modelo se descarga una vez; la generación funciona sin Internet.", wraplength=800).pack(anchor="w", pady=(0, 12))
        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Label(row, text="Modelo:").pack(side="left")
        self.models = ttk.Combobox(row, textvariable=self.variant, values=list(VARIANTS), state="readonly", width=19)
        self.models.pack(side="left", padx=8)
        self.download = ttk.Button(row, text="Descargar / verificar modelo", command=lambda: self.start(["download", self.variant.get()]))
        self.download.pack(side="left")
        ttk.Label(row, text="Hilos:").pack(side="left", padx=(18, 4))
        ttk.Spinbox(row, from_=1, to=8, textvariable=self.threads, width=3).pack(side="left")
        ttk.Label(row, text="Tomas:").pack(side="left", padx=(12, 4))
        ttk.Spinbox(row, from_=1, to=10, textvariable=self.takes, width=3).pack(side="left")
        ttk.Label(frame, text="Las tomas son variantes para escuchar y elegir. Este motor todavía no permite fijar semillas ni temperatura del Predictor por separado.", wraplength=800).pack(anchor="w", pady=(6, 0))
        ttk.Label(frame, text="Audio de referencia (3–30 s, una sola voz; MP3, WAV, M4A…)").pack(anchor="w", pady=(12, 4))
        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Entry(row, textvariable=self.audio).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Elegir audio", command=self.choose_audio).pack(side="left", padx=(8, 0))
        ttk.Label(frame, text="Transcripción exacta de TODO el fragmento de referencia (obligatoria para ICL):").pack(anchor="w", pady=(12, 4))
        self.transcript = tk.Text(frame, height=4, wrap="word", font=("Segoe UI", 10))
        self.transcript.pack(fill="x")
        ttk.Label(frame, text="Texto que querés generar:").pack(anchor="w", pady=(12, 4))
        self.text = tk.Text(frame, height=7, wrap="word", font=("Segoe UI", 11))
        self.text.pack(fill="both", expand=True)
        self.text.insert("1.0", "Hola, esta es una prueba de voz generada de forma local.")
        row = ttk.Frame(frame)
        row.pack(fill="x", pady=12)
        self.run = ttk.Button(row, text="Generar en español", command=self.generate)
        self.run.pack(side="left")
        self.stop = ttk.Button(row, text="Detener", state="disabled", command=self.stop_process)
        self.stop.pack(side="left", padx=8)
        ttk.Button(row, text="Abrir resultados", command=self.open_outputs).pack(side="left")
        ttk.Label(frame, textvariable=self.status).pack(anchor="w")
        self.log = tk.Text(frame, height=9, wrap="word", state="disabled", font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, pady=(8, 0))
        self.after(150, self.poll)
        self.protocol("WM_DELETE_WINDOW", self.close)

    def choose_audio(self):
        chosen = filedialog.askopenfilename(title="Elegir referencia de una sola voz", filetypes=[("Audio", "*.wav *.mp3 *.m4a *.flac *.ogg *.opus"), ("Todos", "*.*")])
        if chosen:
            self.audio.set(chosen)

    def generate(self):
        transcript = self.transcript.get("1.0", "end").strip()
        text = self.text.get("1.0", "end").strip()
        if not self.audio.get() or not transcript or not text:
            messagebox.showerror("Faltan datos", "Elegí un audio, escribí su transcripción exacta y el texto nuevo.")
            return
        try:
            threads, takes = int(self.threads.get()), int(self.takes.get())
            if not 1 <= threads <= 8 or not 1 <= takes <= 10:
                raise ValueError
        except ValueError:
            messagebox.showerror("Valores inválidos", "Elegí entre 1 y 8 hilos y entre 1 y 10 tomas.")
            return
        self.start(["generate", "--variant", self.variant.get(), "--reference", self.audio.get(), "--ref-text", transcript,
                    "--text", text, "--threads", str(threads), "--takes", str(takes)])

    def start(self, arguments):
        if self.process is not None:
            return
        self.run.configure(state="disabled")
        self.download.configure(state="disabled")
        self.models.configure(state="disabled")
        self.stop.configure(state="normal")
        self.status.set("Trabajando… El i5 puede tardar varios minutos. El registro muestra el progreso.")
        environment = os.environ.copy()
        environment["PYTHONIOENCODING"] = "utf-8"
        try:
            self.process = subprocess.Popen([sys.executable, "-u", str(Path(__file__).with_name("mivoz.py")), *arguments],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", env=environment,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError as error:
            self.messages.put(str(error) + "\n")
            self.finish(False)
            return
        process = self.process
        def reader():
            for line in process.stdout:
                self.messages.put(line)
            self.messages.put(("finished", process.wait()))
        threading.Thread(target=reader, daemon=True).start()

    def finish(self, success):
        self.process = None
        self.run.configure(state="normal")
        self.download.configure(state="normal")
        self.models.configure(state="readonly")
        self.stop.configure(state="disabled")
        self.status.set("Listo. Revisá el registro y los resultados." if success else "La operación terminó sin completarse. Revisá el registro.")

    def poll(self):
        while not self.messages.empty():
            item = self.messages.get_nowait()
            if isinstance(item, tuple):
                self.finish(item[1] == 0)
            else:
                self.log.configure(state="normal")
                self.log.insert("end", item)
                if int(self.log.index("end-1c").split(".")[0]) > 1200:
                    self.log.delete("1.0", "250.0")
                self.log.see("end")
                self.log.configure(state="disabled")
        self.after(150, self.poll)

    def stop_process(self):
        if self.process is not None:
            self.process.terminate()
            self.status.set("Deteniendo. Los bloques ya terminados se conservan.")

    def open_outputs(self):
        directory = DATA / "outputs"
        directory.mkdir(parents=True, exist_ok=True)
        os.startfile(directory)

    def close(self):
        if self.process is not None:
            if not messagebox.askyesno("Generación en curso", "¿Cerrar y detener la operación actual? Los bloques completos ya guardados se conservan."):
                return
            self.process.terminate()
        self.destroy()


if __name__ == "__main__":
    Application().mainloop()
