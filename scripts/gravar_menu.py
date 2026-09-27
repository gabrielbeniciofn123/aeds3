"""Executa o menu Java em PTY e produz transcrição, eventos e vídeo sem áudio.

O vídeo reproduz as entradas e saídas capturadas, com tempo editado para leitura.
Não é uma gravação da área de trabalho. O banco e a compilação são temporários.
Produção do vídeo: Python, Pillow, imageio-ffmpeg e uma fonte monoespaçada.
Uso no macOS/Linux: python3 scripts/gravar_menu.py
"""
from __future__ import annotations

import codecs
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import shutil
import struct
import subprocess
import tempfile
import termios
import time

from PIL import Image, ImageDraw, ImageFont
import imageio_ffmpeg


ROOT = Path(__file__).resolve().parents[1]
VIDEO = ROOT / "video/tp2"
RENDER = VIDEO / "render"
FPS = 8
WIDTH, HEIGHT = 1920, 1080
COLS, ROWS = 103, 23


def java_tools():
    for home in [ROOT / ".tools/jdk/Contents/Home", Path(os.environ.get("JAVA_HOME", "/nao-definido"))]:
        if (home / "bin/javac").is_file():
            return str(home / "bin/java"), str(home / "bin/javac")
    java, javac = shutil.which("java"), shutil.which("javac")
    if java and javac:
        return java, javac
    raise RuntimeError("Instale JDK 11 ou superior para gravar a execução real.")


class SessaoPTY:
    def __init__(self, command, cwd):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
        environment = dict(os.environ, LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")
        self.process = subprocess.Popen(command, cwd=cwd, stdin=slave, stdout=slave,
                                        stderr=slave, close_fds=True, env=environment)
        os.close(slave)
        self.start = time.monotonic()
        self.decoder = codecs.getincrementaldecoder("utf-8")()
        self.output = ""
        self.events = []
        self.stages = []
        self.closed = False

    def event(self, kind, text):
        self.events.append({"tempo_real": round(time.monotonic() - self.start, 6),
                            "tipo": kind, "texto": text})

    def read(self, timeout=0.1):
        if self.closed:
            return
        ready, _, _ = select.select([self.master], [], [], timeout)
        if ready:
            try:
                chunk = os.read(self.master, 65536)
            except OSError as exc:
                if exc.errno != errno.EIO:
                    raise
                chunk = b""
            if not chunk:
                self.closed = True
                return
            text = self.decoder.decode(chunk).replace("\r\n", "\n")
            self.output += text
            self.event("saida", text)

    def wait(self, marker, offset=0):
        deadline = time.monotonic() + 20
        while marker not in self.output[offset:]:
            if time.monotonic() >= deadline or self.closed:
                raise RuntimeError(f"O menu não exibiu {marker!r}. Última saída:\n{self.output[-2500:]}")
            self.read()
        self.stages[-1]["quadros"].append(self.output)

    def answer(self, value, next_prompt):
        offset = len(self.output)
        self.event("entrada", value + "\n")
        os.write(self.master, (value + "\n").encode("utf-8"))
        self.wait(next_prompt, offset)

    def stage(self, name, duration):
        self.stages.append({"titulo": name, "duracao": duration, "quadros": []})

    def finish(self):
        code = self.process.wait(timeout=10)
        while not self.closed:
            self.read()
        os.close(self.master)
        if code != 0:
            raise RuntimeError(f"MainTP2 encerrou com código {code}.")

    def abort(self):
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
        try:
            os.close(self.master)
        except OSError:
            pass


def capture(java, javac):
    with tempfile.TemporaryDirectory(prefix="tp2-menu-real-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = [str(p) for p in sorted((ROOT / "src").rglob("*.java"))]
        subprocess.run([javac, "-encoding", "UTF-8", "--release", "11", "-d", str(classes), *sources], check=True)
        command = [java, "-Dfile.encoding=UTF-8", "-Xmx1g", "-cp", "classes", "MainTP2",
                   "--pasta", "base", "--ordem", "4", "--percentual", "2"]
        session = SessaoPTY(command, work)
        try:
            session.stage("Menu e seleção do índice", 5)
            session.wait("Escolha: ")

            session.stage("CREATE · lista invertida selecionada", 10)
            session.answer("2", "Índice desta operação")
            session.answer("3", "Nome: ")
            session.answer("Carro demonstracao", "Características separadas por |: ")
            session.answer("gas|automatic", "Ano: ")
            session.answer("2022", "Data AAAA-MM-DD: ")
            session.answer("2026-09-24", "Escolha: ")

            session.stage("READ · busca pelo hash", 7)
            session.answer("3", "Índice desta operação")
            session.answer("2", "ID: ")
            session.answer("1", "Escolha: ")

            session.stage("LISTAS · ano 2022 E característica gas", 7)
            session.answer("6", "Ano (ENTER para não filtrar): ")
            session.answer("2022", "Característica inteira")
            session.answer("gas", "Escolha: ")

            session.stage("UPDATE · árvore B+ e novo endereço", 10)
            session.answer("4", "Índice desta operação")
            session.answer("1", "ID a atualizar: ")
            session.answer("1", "Nome [Carro demonstracao]: ")
            session.answer("Carro demonstracao com nome maior", "Características separadas por |")
            session.answer("", "Ano [2022]: ")
            session.answer("2025", "Data AAAA-MM-DD")
            session.answer("", "Escolha: ")

            session.stage("DELETE · lista invertida e confirmação", 9)
            session.answer("5", "Índice desta operação")
            session.answer("3", "Ano (ENTER para não filtrar): ")
            session.answer("2025", "Característica inteira")
            session.answer("gas", "ID a excluir: ")
            session.answer("1", "Digite EXCLUIR para confirmar: ")
            session.answer("EXCLUIR", "Escolha: ")

            session.stage("Auditoria de dados e todos os índices", 7)
            session.answer("8", "Escolha: ")

            session.stage("Encerramento normal", 3)
            session.answer("0", "Base salva. Até mais!")
            session.finish()
        except BaseException:
            session.abort()
            raise

        required = ["Carro criado: id=1", "Índice: LISTA | id=1 | endereço=4",
                    "Índice: HASH | id=1 | endereço=4", "Total: 1 (exibidos 1)",
                    "Índice: LISTA | ano=2022 | característica=gas | resultados=1",
                    "Atualizado. Endereço 4 ->", "Lápide marcada e entradas removidas de todos os índices.",
                    "AUDITORIA OK: 0 registros ativos", "Base salva. Até mais!"]
        for value in required:
            if value not in session.output:
                raise RuntimeError(f"A execução real não confirmou: {value}")
        if "Operação não concluída" in session.output or "Não foi possível executar" in session.output:
            raise RuntimeError("O menu informou falha durante a gravação.")
        return session, command


def font_path():
    candidates = [Path("/System/Library/Fonts/Menlo.ttc"),
                  Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf")]
    for path in candidates:
        if path.is_file():
            return str(path)
    raise RuntimeError("Fonte monoespaçada Menlo ou DejaVuSansMono não encontrada.")


def terminal_lines(text):
    # Apenas quebra visual de linha: os caracteres e a ordem da saída são mantidos.
    lines = []
    for line in text.replace("\r", "").expandtabs(4).split("\n"):
        lines.extend([line[i:i + COLS] for i in range(0, len(line), COLS)] or [""])
    return lines[-ROWS:]


def frame(text, title, position, duration, font):
    im = Image.new("RGB", (WIDTH, HEIGHT), "#101a28")
    draw = ImageDraw.Draw(im)
    heading = ImageFont.truetype(font, 31)
    mono = ImageFont.truetype(font, 28)
    small = ImageFont.truetype(font, 22)
    draw.text((56, 32), "MAIN TP2  |  EXECUÇÃO REAL EM TERMINAL", font=heading, fill="#88dfb3")
    draw.text((56, 82), title, font=heading, fill="#f4f7fb")
    draw.rounded_rectangle((42, 140, 1878, 1004), radius=14, fill="#09111c", outline="#2a3a4b", width=2)
    for index, line in enumerate(terminal_lines(text)):
        draw.text((64, 158 + index * 35), line, font=mono, fill="#dce8f2")
    draw.text((56, 1025), "Captura PTY · entradas automatizadas · tempo editado para leitura · saídas preservadas",
              font=small, fill="#a6b4c5")
    draw.rectangle((0, 1071, int(WIDTH * position / duration), 1079), fill="#88dfb3")
    return im


def render(session):
    RENDER.mkdir(parents=True, exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    output = RENDER / "menu.mp4"
    duration = sum(stage["duracao"] for stage in session.stages)
    font = font_path()
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
               "-vcodec", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}",
               "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "ultrafast",
               "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    elapsed = 0
    try:
        for number, stage in enumerate(session.stages, 1):
            snapshots = stage["quadros"]
            frame_count = round(stage["duracao"] * FPS)
            # A segunda metade de cada etapa mantém o resultado final visível.
            for index in range(frame_count):
                part = min(len(snapshots) - 1, int(index * 2 * len(snapshots) / frame_count))
                picture = frame(snapshots[part], stage["titulo"], elapsed + index / FPS, duration, font)
                process.stdin.write(picture.tobytes())
                if index == frame_count - 1:
                    picture.save(RENDER / f"menu-{number:02d}.png")
            elapsed += stage["duracao"]
            print(f"Menu: etapa {number}/{len(session.stages)} renderizada", flush=True)
        process.stdin.close()
        if process.wait(timeout=60) != 0:
            raise RuntimeError("FFmpeg não concluiu o vídeo do menu.")
    except BaseException:
        process.kill()
        process.wait()
        raise
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(output),
                    "-f", "null", "-"], check=True)
    print(f"Vídeo do menu verificado: {duration}s, {WIDTH}x{HEIGHT}, {FPS} fps, sem áudio.", flush=True)
    return duration


def main():
    java, javac = java_tools()
    session, command = capture(java, javac)
    VIDEO.mkdir(parents=True, exist_ok=True)
    (ROOT / "docs/DEMONSTRACAO_MENU_ATUAL.txt").write_text(
        "EXECUÇÃO REAL DE MainTP2 EM PTY\n"
        "Entradas automatizadas; base vazia e compilação em diretório temporário isolado.\n"
        "Vídeo: reprodução do terminal com tempo editado para leitura, saídas preservadas.\n"
        "Comando (Java localizado pelo script): java " + " ".join(command[1:]) + "\n\n"
        + session.output, encoding="utf-8")
    duration = render(session)
    manifest = {"origem": "MainTP2 executado em PTY real; banco temporário isolado",
                "edicao": "Tempos editados para leitura; conteúdo e sequência das saídas preservados",
                "comando": command, "duracao_video_segundos": duration, "fps": FPS,
                "resolucao": [WIDTH, HEIGHT], "audio": False,
                "eventos": session.events, "etapas": session.stages}
    (VIDEO / "menu_eventos.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Captura, verificações do cenário e decodificação integral concluídas.", flush=True)


if __name__ == "__main__":
    main()
