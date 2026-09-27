# Roteiro de referência das 12 gravações — TP2 Carros

As 12 gravações enviadas separadamente pelo integrante foram incorporadas ao vídeo final `video/tp2/video_tp2.mp4`. As cenas e as etapas do menu foram ajustadas às falas. Os arquivos originais estão em `video/tp2/audios_originais`, numerados de `fala_01.mp4` a `fala_12.mp4`, e os capítulos estão em `video/tp2/SINCRONIZACAO.md`.

O texto abaixo é o roteiro de referência usado para preparar as gravações, não uma transcrição literal dos áudios. Os títulos e as indicações de pausa são orientações de gravação. Para uma futura regravação:

- Grave uma fala por arquivo, em um lugar silencioso, sem música ou o vídeo tocando ao fundo.
- Deixe cerca de 2 segundos de silêncio no início, entre os blocos e no final.
- Fale naturalmente; ajuste os tempos das cenas às novas gravações.
- Se errar uma frase, faça uma pausa de 2 segundos e repita a frase inteira. A repetição pode ser cortada na edição.
- Procure ficar entre 8 e 9 minutos. O vídeo final precisa ter no máximo 10 minutos.
- Mantenha os arquivos na ordem das 12 cenas.

Pronúncia: B+ é “B mais”; ID pode ser lido como “identificador”; 2^p é “dois elevado a p”. Os endereços e resultados citados abaixo correspondem às execuções mostradas no vídeo.

A duração e as verificações da versão final estão em `video/tp2/VERIFICACAO.txt`. O procedimento para reproduzir a montagem com os áudios originais está em `video/tp2/LEIA-ME.md`.

## 1. Apresentação

Olá! Neste vídeo vou apresentar o trabalho prático dois de AEDS três, sobre uma base de carros. O projeto continua o trabalho anterior em Java e utiliza a mesma base de cem mil anúncios. Agora, o acesso aos registros é feito por índices persistentes: uma árvore B mais, um hash estendido e duas listas invertidas. Vou explicar as escolhas da implementação, mostrar o funcionamento do cadastro e apresentar os resultados dos testes.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 2. Arquivo de dados e índices

O arquivo de dados mantém o formato do trabalho anterior. No início fica o último identificador usado. Depois vêm os registros, cada um com uma lápide, o tamanho e os dados do carro. A lápide indica se aquela versão está ativa ou excluída. Na árvore e no hash, cada identificador fica associado à posição do registro no arquivo. Essa posição usa o tipo long do Java. Assim, depois de consultar o índice, o programa acessa diretamente o endereço encontrado.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 3. Escolha da árvore B+

A árvore escolhida foi a B mais. Nela, os pares de identificador e endereço ficam nas folhas, enquanto as páginas internas orientam a busca. As folhas também são encadeadas, permitindo percorrer os identificadores em ordem. A ordem da árvore é um parâmetro. No padrão dezesseis, uma página interna pode ter até dezesseis filhos e quinze chaves. Quando uma página enche, acontece uma divisão. Na exclusão, a árvore pode fazer empréstimos entre páginas ou fusões. O exemplo na tela usa ordem quatro, para tornar essas mudanças mais visíveis.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 4. Hashing estendido e regra dos 2%

O hash também usa o identificador, porque ele é único, estável e permite comparar a busca do mesmo carro com a árvore. A função exigida é o identificador módulo dois elevado à profundidade global. Cada bucket guarda pares de identificador e posição. Quando ele fica cheio, é dividido. Se a profundidade local já for igual à global, o diretório também dobra. A capacidade é dois por cento da base inicial. Portanto, com cem mil carros, cada bucket aceita dois mil pares. Para bases pequenas, arredondamos para cima e usamos no mínimo um. Essa capacidade não muda a cada cadastro.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 5. As duas listas invertidas

As listas invertidas ficam em dois arquivos separados: um para o ano e outro para as características. Escolhemos esses campos porque permitem filtrar os carros por critérios úteis e combinar os resultados. Cada termo aponta para identificadores e endereços. Ao informar ano e característica juntos, o sistema faz uma interseção: só aparecem os carros que atendem aos dois filtros. No exemplo da tela, ano dois mil e vinte e dois e característica gas encontram os carros dois e oito. A pesquisa normaliza acentos, maiúsculas e espaços. Cada característica completa é tratada como um termo.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 6. Escolha do índice em cada operação

O menu permite escolher árvore B mais, hash ou lista invertida em cada operação de criação, consulta, atualização e exclusão. Na criação, todos os índices recebem o novo registro, e o método escolhido confirma o endereço inserido. Nas outras operações, ele localiza o carro. Pela lista, também informamos o ano ou a característica. Neste exemplo, os três métodos localizaram o mesmo carro no endereço sessenta e seis. Independentemente do índice escolhido para buscar, todas as estruturas são atualizadas quando os dados mudam.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 7. Atualização no mesmo endereço

Primeiro alteramos apenas o ano, usando o hash. Como o ano é um inteiro de tamanho fixo, o registro continua ocupando a mesma quantidade de bytes e permanece no endereço sessenta e seis. Mesmo sem mudar de posição, as listas precisam ser atualizadas. O identificador sai da lista do ano antigo e entra na lista do novo ano. A consulta confirma que o filtro antigo deixou de encontrar esse carro. Isso demonstra que a atualização mantém os índices coerentes com os dados.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 8. Atualização com mudança de endereço

Agora aumentamos o nome e trocamos as características. Como o registro passa a ter outro tamanho, a versão antiga recebe uma lápide e a nova é gravada no final do arquivo, mantendo o identificador. O novo endereço é setecentos e trinta e três. A árvore, o hash e as listas passam a apontar para essa nova posição. A característica anterior deixa de encontrar o carro. Assim, o teste confere tanto a mudança de endereço quanto a atualização dos filtros.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 9. Exclusão e persistência

Neste exemplo, a exclusão usa a lista invertida. O sistema marca a lápide no arquivo de dados e remove as referências dos quatro índices. Depois disso, nenhuma das três formas de consulta encontra o carro excluído. A auditoria compara os registros ativos com os índices e verifica as regras internas das estruturas. Em seguida, os arquivos são fechados e abertos novamente, e a auditoria continua correta. Isso confirma a persistência nos arquivos, inclusive depois da exclusão.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 10. Testes com os 100 mil carros

Também foi executada a carga completa dos cem mil carros. Os testes comparam buscas da árvore e do hash com uma referência sequencial, verificam as listas, fazem alterações e reabrem os arquivos. A busca pelo ano dois mil e quatorze e pela característica gas retornou cinco mil seiscentos e noventa e três carros, exatamente como a referência. A auditoria conferiu todos os registros ativos. Há ainda testes aleatórios e testes de recuperação de falhas durante as escritas. Os tempos mostrados na tela são medições do ambiente utilizado. Os comandos e os resultados acompanham a entrega.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 11. Demonstração do menu

Este trecho mostra o menu sendo executado em uma base temporária separada. As entradas foram automatizadas e as saídas vieram da execução real do programa. O tempo foi editado para facilitar a leitura. Primeiro, selecionamos a lista invertida e cadastramos um carro. Depois, consultamos seu identificador pelo hash e fazemos uma pesquisa combinando ano e característica. A atualização é feita pela árvore B mais e muda o endereço do registro. Em seguida, a exclusão usa a lista invertida. A auditoria final confirma que a base ficou sem registros ativos e que os índices continuam coerentes.

*[Pausa de 2 segundos — não leia esta instrução.]*

## 12. Recuperação, limites e encerramento

Para manter a coerência, o programa usa um diário de recuperação e sincroniza os índices antes de confirmar a operação. A importação também utiliza arquivo temporário e backup. A aplicação permite um processo por base. O diretório do hash e as entradas das listas ficam em memória. O hash limita a profundidade a vinte e não faz fusão de buckets depois das exclusões. Essas limitações estão documentadas. Para reproduzir os exemplos, basta executar os scripts de demonstração e de testes. Com isso, foram apresentados os índices, o cadastro, as consultas e a manutenção da coerência entre os arquivos. Obrigado!

*[Pausa de 2 segundos — não leia esta instrução.]*
