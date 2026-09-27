"""Cria entrega final com vídeo narrado e áudios, sem arquivos temporários."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
root=Path(__file__).resolve().parents[1]
dest=root.parent/'TP2-Carros-Entrega-Final.zip'
files=[root/p for p in ['README.md','EQUIPE.txt','ENTREGA.md','.gitignore','compilar.sh','executar.sh','testar.sh','demonstrar.sh','compilar.bat','executar.bat','testar.bat','demonstrar.bat','data/base.csv','temp/.gitkeep']]
for name in ['src','docs','scripts']:
    files.extend(p for p in (root/name).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
files.extend([root/'video/video_tp1.mp4', root/'video/video_tp2.mp4', root/'video/README.md'])
files.extend(p for p in (root/'video/tp2').iterdir() if p.is_file() and p.suffix in ['.mp4','.md','.json','.txt','.log'])
files.extend(p for p in (root/'video/tp2/audios_originais').iterdir() if p.is_file() and p.suffix in ['.mp4','.json'])
temporary=dest.with_suffix('.zip.partial')
with ZipFile(temporary,'w',ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(set(files)): z.write(p,'TP2-Carros/'+p.relative_to(root).as_posix())
with ZipFile(temporary) as z:
    assert z.testzip() is None
    for essential in ['src/MainTP2.java','src/index/ArvoreBMais.java','src/index/HashEstendido.java','src/index/ListaInvertida.java','data/base.csv','video/video_tp1.mp4','video/video_tp2.mp4']:
        assert 'TP2-Carros/'+essential in z.namelist()
    for i in range(1,13):
        assert f'TP2-Carros/video/tp2/audios_originais/fala_{i:02d}.mp4' in z.namelist()
temporary.replace(dest)
checksum=hashlib.sha256(dest.read_bytes()).hexdigest()
dest.with_suffix('.sha256').write_text(checksum+'  '+dest.name+'\n',encoding='utf-8')
print(dest)
print(f'{dest.stat().st_size/1024/1024:.2f} MiB · {len(files)} arquivos · integridade ZIP aprovada')
print('SHA256:',checksum)
