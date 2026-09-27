"""Monta o vídeo TP2 com as 12 gravações do integrante, sem sintetizar voz.

python3 scripts/gerar_video.py
python3 scripts/gerar_video.py --audios-dir /caminho/dos/audios
Dependências de produção: Pillow e imageio-ffmpeg; fontes Arial/Menlo do macOS.
As saídas do menu vêm da captura real preservada em menu_eventos.json.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import textwrap
import wave

from PIL import Image, ImageDraw, ImageFont
import imageio_ffmpeg
from gravar_menu import frame, font_path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'video/tp2'
RENDER = DEST / 'render/voz_integrante'
FF = imageio_ffmpeg.get_ffmpeg_exe()
FPS = 10
RATE = 48000
LEAD = 0.25
TAIL = 0.55
FONT = '/System/Library/Fonts/Supplemental/Arial.ttf'
BOLD = '/System/Library/Fonts/Supplemental/Arial Bold.ttf'
MONO = '/System/Library/Fonts/Menlo.ttc'


def run(args):
    result = subprocess.run([FF, '-hide_banner', '-y', *map(str, args)], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stderr


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def duration(path):
    result = subprocess.run([FF, '-hide_banner', '-i', str(path)], capture_output=True, text=True)
    match = re.search(r'Duration: (\d+):(\d+):([\d.]+)', result.stderr)
    if not match:
        raise RuntimeError('Duração ausente: ' + str(path))
    return int(match[1])*3600 + int(match[2])*60 + float(match[3]), result.stderr


def timestamp(seconds):
    minutes, remaining = divmod(seconds, 60)
    return f'{int(minutes):02d}:{remaining:06.3f}'


def slide(scene):
    i = scene['cena']
    font = lambda n, bold=False: ImageFont.truetype(BOLD if bold else FONT, n)
    im = Image.new('RGB', (1920, 1080), '#101a28')
    d = ImageDraw.Draw(im)
    d.rectangle((64,65,116,73), fill='#88dfb3')
    d.text((132,50), 'AEDS III  /  TP2 CARROS', font=font(25,True), fill='#a6b4c5')
    d.text((64,126), scene['titulo'], font=font(54,True), fill='#f4f7fb')
    d.text((66,203), scene['subtitulo'], font=font(29), fill='#88dfb3')
    y = 303
    for line in textwrap.wrap(scene['apoio'], 27):
        d.text((68,y), line, font=font(39,True), fill='#f4f7fb')
        y += 54
    d.text((70,780), f'{i:02d} / 12', font=font(58,True), fill='#88dfb3')
    for y, text, size in [(866,'Grupo 15',24),(902,'Gabriel Benicio Fonseca',22),(934,'Rhayner Martins',22)]:
        d.text((70,y),text,font=font(size),fill='#a6b4c5')
    d.rounded_rectangle((690,284,1854,990),radius=20,fill='#09111c',outline='#2a3a4b',width=2)
    d.text((722,305),scene['rotulo'],font=font(20,True),fill='#88dfb3')
    lines = []
    for line in scene['linhas']:
        lines.extend(textwrap.wrap(line,78,break_long_words=True,replace_whitespace=False) or [''])
    if len(lines)>27:
        raise RuntimeError(f'Cena {i}: texto excede área disponível.')
    size, lineheight = (23,28) if len(lines)<=23 else (19,23)
    for j,line in enumerate(lines):
        d.text((722,355+j*lineheight),line,font=ImageFont.truetype(MONO,size),fill='#dce8f2')
    d.text((70,1031),'Voz do integrante · Transcrições reais do programa · '+date.today().strftime('%d/%m/%Y'),font=font(19),fill='#a6b4c5')
    d.rectangle((0,1071,int(1920*i/12),1079),fill='#88dfb3')
    path = RENDER/f'{i:02d}.png'
    im.save(path)
    return path


def audio_input(folder, number):
    direct = folder/f'fala_{number:02d}.mp4'
    if direct.is_file():
        return direct
    found = [p for p in folder.iterdir() if p.is_file() and re.match(rf'Fala {number}(?:\D|$)', p.name)]
    if len(found) != 1:
        raise RuntimeError(f'Esperado exatamente um áudio para a fala {number}: {found}')
    return found[0]


def prepare_scene(scene, folder):
    i = scene['cena']
    source = audio_input(folder, i)
    raw = RENDER/f'{i:02d}_original.wav'
    run(['-loglevel','error','-i',source,'-map','0:a:0','-vn','-ac','1','-ar',RATE,'-c:a','pcm_s16le',raw])
    with wave.open(str(raw)) as w:
        original = w.getnframes()/w.getframerate()
    seconds = math.ceil((original+LEAD+TAIL)*FPS)/FPS
    # Duas passagens: mesmo alvo perceptivo em todos os clipes, sem acelerar a voz.
    analysis = run(['-i',raw,'-af','highpass=f=60,loudnorm=I=-18:TP=-1.5:LRA=11:print_format=json','-f','null','-'])
    loudness = json.loads(re.search(r'\{\s*"input_i".*?\}',analysis,re.S)[0])
    normalizer = ('highpass=f=60,loudnorm=I=-18:TP=-1.5:LRA=11'
                  f":measured_I={loudness['input_i']}:measured_TP={loudness['input_tp']}"
                  f":measured_LRA={loudness['input_lra']}:measured_thresh={loudness['input_thresh']}"
                  f":offset={loudness['target_offset']}:linear=true:print_format=json")
    audio = RENDER/f'{i:02d}.wav'
    sample_count = round(seconds*RATE)
    padding = f',aresample=48000,adelay=250,apad=whole_len={sample_count},atrim=end_sample={sample_count},asetpts=N/SR/TB'
    log = run(['-i',raw,'-af',normalizer+padding,
               '-ar',RATE,'-ac','1','-c:a','pcm_s16le',audio])
    (RENDER/f'{i:02d}_audio.log').write_text(log)
    with wave.open(str(audio)) as w:
        assert w.getnframes() == round(seconds*RATE)
    if i != 11:
        png = slide(scene)
        run(['-loglevel','error','-loop','1','-framerate',FPS,'-i',png,'-t',seconds,
             '-an','-c:v','libx264','-threads','2','-preset','veryfast','-tune','stillimage',
             '-crf','21','-pix_fmt','yuv420p','-video_track_timescale','10240',RENDER/f'{i:02d}.mp4'])
    result = {'cena':i,'titulo':scene['titulo'],'arquivo_audio':source.name,'sha256_audio':sha(source),
              'duracao_audio_original':round(original,6),'entrada_audio_na_cena':LEAD,
              'duracao':seconds,'cortes_na_fala':False,'velocidade_audio':1.0,
              'narracao':scene['roteiro'],'texto':'Roteiro de referência; não é transcrição literal da gravação.'}
    print(f'Cena {i:02d}/12 preparada: {seconds:.1f}s, fala integral.',flush=True)
    return result


def render_menu(seconds, audio_hash):
    capture = json.loads((DEST/'menu_eventos.json').read_text())
    sync = json.loads((DEST/'sincronizacao_menu.json').read_text())
    if sync['sha256_audio'] != audio_hash:
        raise RuntimeError('A fala 11 mudou. Atualize os marcadores em sincronizacao_menu.json antes de montar.')
    cues = sync['etapas']
    assert [x['etapa'] for x in cues] == list(range(8))
    boundaries = [0] + [round((x['inicio_audio']+LEAD)*FPS) for x in cues[1:]] + [round(seconds*FPS)]
    assert all(b>a for a,b in zip(boundaries,boundaries[1:])), boundaries
    command = [FF,'-hide_banner','-loglevel','error','-y','-f','rawvideo','-vcodec','rawvideo',
               '-pix_fmt','rgb24','-s','1920x1080','-r',str(FPS),'-i','-','-an','-c:v','libx264',
               '-threads','2','-preset','veryfast','-crf','21','-pix_fmt','yuv420p',
               '-video_track_timescale','10240',str(RENDER/'11.mp4')]
    font = font_path()
    with (RENDER/'menu_render.log').open('w') as log:
        proc = subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
        try:
            for index, stage in enumerate(capture['etapas']):
                start, end = boundaries[index:index+2]
                snapshots = stage['quadros']
                cached = {}
                for pos in range(end-start):
                    part = min(len(snapshots)-1,int(pos*len(snapshots)/max(1,(end-start)*0.55)))
                    # Todo o conteúdo capturado permanece em sua ordem; o resultado tem tempo de leitura.
                    if part not in cached:
                        cached[part] = frame(snapshots[part],stage['titulo'],0,seconds,font)
                    im = cached[part].copy()
                    ImageDraw.Draw(im).rectangle((0,1071,int(1920*(start+pos)/(seconds*FPS)),1079),fill='#88dfb3')
                    proc.stdin.write(im.tobytes())
                    if pos == end-start-1:
                        im.save(RENDER/f'menu-{index+1:02d}.png')
                print(f'Menu sincronizado: {stage["titulo"]} ({start/FPS:.1f}–{end/FPS:.1f}s)',flush=True)
            proc.stdin.close()
            if proc.wait(timeout=60):
                raise RuntimeError('Falha ao renderizar menu; consulte menu_render.log')
        except BaseException:
            proc.kill()
            proc.wait()
            raise
    return [{**cue,'inicio_na_cena':boundaries[i]/FPS,'fim_na_cena':boundaries[i+1]/FPS}
            for i,cue in enumerate(cues)]


def finalize(manifest):
    menu = render_menu(manifest[10]['duracao'], manifest[10]['sha256_audio'])
    total = round(sum(x['duracao'] for x in manifest),3)
    if total >= 600:
        raise RuntimeError('O vídeo excede o limite de 10 minutos.')
    # Uma única codificação AAC ao final evita lacunas de priming entre as falas.
    with wave.open(str(RENDER/'narracao_completa.wav'),'wb') as out:
        out.setparams((1,2,RATE,0,'NONE','not compressed'))
        for s in manifest:
            with wave.open(str(RENDER/f'{s["cena"]:02d}.wav')) as part:
                out.writeframes(part.readframes(part.getnframes()))
    elapsed = 0
    metadata = [';FFMETADATA1','title=TP2 Carros — Grupo 15','artist=Gabriel Benicio Fonseca e Rhayner Martins',
                'comment=Narração do integrante; demonstrações reais do programa.']
    for s in manifest:
        s['inicio'] = round(elapsed,3)
        elapsed = round(elapsed+s['duracao'],3)
        s['fim'] = elapsed
        metadata += ['[CHAPTER]','TIMEBASE=1/1000',f'START={round(s["inicio"]*1000)}',
                     f'END={round(s["fim"]*1000)}',f'title={s["cena"]:02d}. {s["titulo"]}']
    (RENDER/'capitulos.txt').write_text('\n'.join(metadata)+'\n')
    (RENDER/'concat.txt').write_text(''.join(f"file '{s['cena']:02d}.mp4'\n" for s in manifest))
    output = RENDER/'video_tp2_final.mp4'
    run(['-loglevel','warning','-f','concat','-safe','0','-i',RENDER/'concat.txt','-i',RENDER/'narracao_completa.wav',
         '-f','ffmetadata','-i',RENDER/'capitulos.txt','-map','0:v:0','-map','1:a:0','-map_metadata','2',
         '-map_chapters','2','-c:v','copy','-c:a','aac','-b:a','160k','-ar',RATE,
         '-metadata:s:a:0','language=por','-t',total,'-movflags','+faststart',output])
    # Decodificação integral antes de substituir o arquivo final anterior.
    decode = run(['-v','warning','-xerror','-i',output,'-map','0:v:0','-map','0:a:0','-f','null','-'])
    (RENDER/'decodificacao_final.log').write_text(decode)
    actual, probe = duration(output)
    assert abs(actual-total)<0.12, (actual,total)
    assert '1920x1080' in probe and '48000 Hz' in probe
    assert len(re.findall(r'Chapter #',probe))==12
    volume = run(['-i',output,'-map','0:a:0','-af','volumedetect','-f','null','-'])
    audio_audit = run(['-i',output,'-map','0:a:0','-af','ebur128=peak=true','-f','null','-'])
    (RENDER/'volume_final.log').write_text(volume+'\n'+audio_audit)
    output.replace(DEST/'video_tp2.mp4')
    save_json(DEST/'narracao.json',manifest)
    save_json(DEST/'render/voz_integrante/menu_sincronizado.json',menu)
    (DEST/'NARRACAO.md').write_text('# Narração do vídeo TP2\n\n'
        'O vídeo utiliza as 12 gravações fornecidas pelo integrante, na ordem numérica. '
        'Os textos abaixo são o roteiro de referência, e não uma transcrição literal. '
        'As falas foram preservadas integralmente, sem alteração de velocidade; volume normalizado.\n\n'+
        '\n\n'.join(f"## {s['cena']}. {s['titulo']} — {timestamp(s['inicio'])}\n\n{s['narracao']}" for s in manifest)+'\n')
    sync_text = '# Sincronização do vídeo final\n\n12 gravações completas, uma por cena. '
    sync_text += 'Cada cena começa 0,25 s antes de seu áudio e termina pelo menos 0,55 s depois. '
    sync_text += 'A voz mantém a velocidade original. O menu acompanha os marcadores da fala 11.\n\n'
    sync_text += '| Cena | Início | Fim | Conteúdo |\n|---|---|---|---|\n'
    sync_text += '\n'.join(f"| {s['cena']:02d} | {timestamp(s['inicio'])} | {timestamp(s['fim'])} | {s['titulo']} |" for s in manifest)
    sync_text += '\n\n## Menu — tempos dentro da cena 11\n\n'
    sync_text += '\n'.join(f"- {timestamp(x['inicio_na_cena'])}: {x['descricao']}" for x in menu)+'\n'
    (DEST/'SINCRONIZACAO.md').write_text(sync_text)
    final = DEST/'video_tp2.mp4'
    streams = [x.strip() for x in probe.splitlines() if 'Stream #' in x]
    volumes = [x.split(']')[-1].strip() for x in volume.splitlines() if 'mean_volume:' in x or 'max_volume:' in x]
    report = [f'VERIFICAÇÃO DO VÍDEO FINAL — {date.today().strftime("%d/%m/%Y")}',
              f'Duração: {actual:.2f} segundos ({timestamp(actual)}); limite de 600 segundos atendido.',
              'Resolução: 1920 × 1080; 10 fps; vídeo H.264; áudio AAC mono a 48 kHz.',
              '12 gravações próprias incorporadas integralmente e na ordem, com 12 capítulos.',
              'Sem voz sintetizada; sem cortes na fala; velocidade original preservada.',
              'Volume normalizado por gravação: alvo -18 LUFS, teto -1,5 dBTP.',
              'Menu sincronizado por etapas com a fala 11; saída real preservada em menu_eventos.json.',
              'Decodificação integral de vídeo e áudio: APROVADA (FFmpeg, -xerror; código 0).',
              *streams,*volumes,f'Tamanho: {final.stat().st_size} bytes',f'SHA256: {sha(final)}']
    (DEST/'VERIFICACAO.txt').write_text('\n'.join(report)+'\n')
    print('\n'.join(report),flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audios-dir',type=Path,default=DEST/'audios_originais')
    parser.add_argument('--etapa',choices=['tudo','preparar','finalizar'],default='tudo')
    args = parser.parse_args()
    RENDER.mkdir(parents=True,exist_ok=True)
    if args.etapa in ['tudo','preparar']:
        scenes = json.loads((ROOT/'scripts/cenas_video.json').read_text())
        assert [s['cena'] for s in scenes] == list(range(1,13))
        with ThreadPoolExecutor(max_workers=2) as pool:
            manifest = list(pool.map(lambda s:prepare_scene(s,args.audios_dir),scenes))
        save_json(RENDER/'preparacao.json',manifest)
        print(f'Preparação: {sum(s["duracao"] for s in manifest):.1f} segundos.',flush=True)
    if args.etapa in ['tudo','finalizar']:
        manifest = json.loads((RENDER/'preparacao.json').read_text())
        finalize(manifest)


if __name__ == '__main__':
    main()
