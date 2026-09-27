# Vídeo do TP2

`video_tp2.mp4` apresenta a implementação em 12 cenas, com as gravações de voz fornecidas pelo integrante. A duração final, a conferência do limite de 10 minutos, os formatos de áudio e vídeo e o checksum do MP4 estão em `VERIFICACAO.txt`.

Cada gravação corresponde à cena de mesmo número. Os tempos das cenas acompanham as falas, e as etapas da demonstração do menu foram sincronizadas com a explicação das operações. `SINCRONIZACAO.md` registra os capítulos; `narracao.json` guarda os tempos da montagem. `NARRACAO.md` contém o roteiro de referência, que pode apresentar pequenas diferenças em relação às palavras efetivamente gravadas.

O vídeo exibe transcrições reais de `DemonstracaoTP2` e `TesteTP2 --base-100k`, seguidas de uma sessão real de `MainTP2` capturada por PTY. O terminal foi renderizado a partir das entradas e saídas preservadas em `menu_eventos.json`, com o tempo editado para acompanhar a fala. A transcrição dessa sessão está em `docs/DEMONSTRACAO_MENU_ATUAL.txt`, a partir da raiz do projeto.

O material cobre a escolha da B+, ordem parametrizada, justificativa do ID no hash, capacidade de 2%, duas listas, pesquisa combinada, CRUD, mudança de endereço, exclusão, persistência, testes e limites. A sessão do menu mostra criação via Lista, consulta via Hash, atualização via B+ e exclusão via Lista. Os testes exercitam o CRUD completo pelos três métodos. O exemplo didático inicia vazio e usa X=1 para evidenciar divisões; a base real usa 100.000 registros e X=2.000.

Os 12 arquivos originais acompanham o pacote na pasta `audios_originais`, com nomes de `fala_01.mp4` a `fala_12.mp4`. `sincronizacao_menu.json` contém os pontos usados para ajustar a demonstração à fala 11. As evidências de execução exibidas no vídeo permanecem nos documentos terminados em `ATUAL.txt`.

Para reproduzir a montagem no macOS, são necessários Python 3, Pillow, imageio-ffmpeg e as fontes Arial e Menlo. Na raiz do projeto, execute:

```sh
python3 scripts/gerar_video.py
```

Por padrão, o script lê os 12 arquivos de `video/tp2/audios_originais`. Para indicar outra pasta com os mesmos nomes numerados:

```sh
python3 scripts/gerar_video.py --audios-dir /caminho/para/audios
```

Se a gravação da fala 11 for substituída, ajuste também os tempos e o SHA256 em `sincronizacao_menu.json`. O script verifica esse vínculo para evitar aplicar marcadores antigos a um áudio diferente.

A montagem reutiliza `menu_eventos.json` e os logs preservados, sem executar novamente o programa Java. A pasta `render` contém arquivos temporários de produção e é recriada pelo script. O vídeo final já acompanha a entrega e pode ser assistido diretamente.

Para executar novamente os testes ou a demonstração Java, use as instruções do `README.md` na raiz do projeto. As ferramentas de produção audiovisual são independentes das dependências do aplicativo Java.
