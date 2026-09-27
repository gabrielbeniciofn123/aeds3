# Guia de estudo para apresentar o TP2

As respostas abaixo acompanham a implementação deste projeto. Use-as para entender o código e explicar com suas palavras. Os exemplos com conjuntos e endereços são didáticos, salvo quando obtidos diretamente de uma execução.

## 1. Qual é a diferença principal entre TP1 e TP2?

No TP1, uma busca por ID percorria registros até encontrar o carro. No TP2, árvore, hash ou lista invertida fornece o endereço. O arquivo de dados mantém seu formato, mas o caminho de localização muda. Carga, reconstrução e auditoria ainda precisam de varreduras; uma consulta normal pelo índice não percorre toda a base.

**Onde olhar:** `ArquivoIndexado.localizar` e `ArquivoIndexado.lerPosicao`.

## 2. Por que guardar ID e posição, se o ID já identifica o carro?

O ID responde “qual carro”; a posição responde “onde estão os bytes dele”. Como os registros têm tamanho variável, não é possível calcular o endereço apenas multiplicando o ID por um tamanho fixo. O índice guarda essa associação.

## 3. Para que serve a lápide?

A lápide informa se uma versão física do registro está ativa. Zero indica ativo e um indica excluído. Na exclusão, os bytes podem continuar no arquivo, mas deixam de representar um carro disponível. Uma atualização que muda o tamanho também deixa a versão antiga com lápide.

## 4. Por que foi escolhida a B+?

Porque ela reúne várias chaves em páginas e concentra os endereços dos registros nas folhas. Os nós internos orientam a busca, todas as folhas têm a mesma profundidade e seu encadeamento permite percorrer os IDs em ordem. A implementação trabalha com páginas em disco e trata inserção e remoção sem reconstruir toda a árvore.

## 5. O que significa ordem 16?

No projeto, significa até 16 filhos em um nó interno e até 15 chaves em uma página. Não significa que a árvore tenha 16 níveis. A ordem é um parâmetro; a altura é uma consequência da quantidade de registros e da ocupação das páginas. Use `--ordem 4` para observar divisões com poucos registros.

## 6. O que acontece quando uma página da B+ enche?

A inserção pode criar uma página à direita e dividir as entradas. Na folha, os pares continuam nas folhas e um separador é levado ao pai. Em nós internos, a divisão redistribui filhos e separadores. Se o pai também ficar cheio, a divisão pode continuar até a raiz. Uma raiz dividida dá origem a uma nova raiz, aumentando a altura.

**Onde olhar:** método privado `ArvoreBMais.inserir`.

## 7. Como a árvore remove uma chave sem ficar desbalanceada?

Depois de remover, o algoritmo verifica a ocupação da página. Se ela ficar abaixo do mínimo, tenta receber uma entrada ou um filho de um irmão que tenha folga. Se isso não for possível, faz uma fusão. O pai perde uma referência e também pode precisar de ajuste. Uma raiz interna vazia é substituída por seu único filho. Páginas liberadas são reutilizáveis.

## 8. O que é o separador de um nó interno?

É o menor ID do filho imediatamente à direita. Se um filho passa a ter outro menor ID, esse separador precisa ser atualizado. Ele não é um endereço do carro. Os endereços de carros ficam nas folhas; os ponteiros internos levam a outras páginas do índice.

## 9. Qual a diferença entre profundidade global e local no hash?

A profundidade global `p` define quantas entradas o diretório possui: `2^p`. A profundidade local informa quantos bits distinguem aquele bucket. Se a local for menor que a global, mais de uma entrada do diretório pode apontar para o mesmo bucket.

Por exemplo, com profundidade global 3, o diretório tem 8 entradas. Um bucket de profundidade local 2 deve ter `2^(3 - 2) = 2` referências compatíveis com seu padrão de bits.

## 10. A operação com máscara realmente implementa o módulo pedido?

Sim, para os IDs positivos utilizados pelo sistema. Quando o divisor é uma potência de dois, o resto corresponde aos bits menos significativos. Assim, `id & ((1 << p) - 1)` equivale a `id % (1 << p)`. Para `id = 13` e `p = 3`, ambos resultam em 5.

## 11. O hash dobra sempre que um bucket fica cheio?

Não. Um bucket cheio precisa ser dividido, mas o diretório só precisa dobrar quando a profundidade local desse bucket é igual à global. Se a local for menor, o diretório já tem referências suficientes para distinguir os dois buckets após a divisão.

## 12. Como foi calculada a capacidade dos buckets?

Foi usada a fórmula `max(1, ceil(N_inicial × percentual / 100))`. Com 100.000 carros e 2%, X vale 2.000. O tamanho inicial é persistido e não muda a cada inclusão ou exclusão. O arredondamento e o mínimo de um par permitem trabalhar também com bases pequenas ou vazias.

O padrão 2% segue o enunciado desta entrega. O parâmetro de percentual permite experimentos, mas a apresentação e a validação da base oficial devem usar 2%.

## 13. A busca no hash é sempre O(1)?

O acesso à referência no diretório é direto, mas esta implementação percorre os pares ocupados dentro do bucket para achar o ID. Esse trecho custa O(X) no pior caso, sendo X a capacidade. Divisões também têm custo de redistribuição e podem exigir ampliar o diretório. É mais preciso descrever essas etapas do que prometer tempo constante para qualquer operação.

## 14. O que acontece se as colisões continuarem indefinidamente?

O código limita a profundidade global a 20. Se não houver bits suficientes dentro desse limite para separar as chaves que lotariam o bucket, a inserção é recusada com uma mensagem de erro. O coordenador do CRUD tenta desfazer a alteração e reconstruir os índices. Aumentar a capacidade e reconstruir o hash é uma alternativa quando os dados exigem outra configuração.

## 15. O que acontece com os buckets após uma exclusão?

O último par ocupado toma o lugar do par retirado e a quantidade diminui. A implementação não faz fusão de buckets nem encolhe o diretório. As pesquisas continuam corretas, mas o arquivo pode ficar com espaço ocioso. Trata-se de uma limitação assumida, e não de uma funcionalidade implementada parcialmente e ocultada.

## 16. O que é um posting e por que há duas listas?

Posting é uma entrada que liga um termo a um registro. Aqui ela contém ID e posição. Uma lista agrupa postings por ano; a outra, por característica. Isso atende à exigência de dois arquivos e permite combinar dois critérios úteis do cadastro.

Uma característica como `8 cylinders` vira um único termo normalizado. Repeti-la no mesmo carro não cria postings duplicados.

## 17. Como funciona a pesquisa com ano e característica?

O programa consulta cada lista e conserva os IDs e endereços que aparecem nas duas. É uma interseção, ou condição E. Se o ano trouxer `{2, 5, 8}` e a característica trouxer `{1, 5, 8, 9}`, o resultado será `{5, 8}`. Depois os carros são lidos diretamente pelos endereços selecionados.

Se só um filtro estiver preenchido, a pesquisa usa apenas aquela lista. Se nenhum for preenchido, o programa pede um filtro, pois essa operação foi feita para consultar as listas.

## 18. A lista invertida é uma busca textual por qualquer palavra?

Não. O índice de características considera cada item inteiro da lista do carro. Ele ignora diferenças de acentos, caixa e espaços, mas não faz tradução, aproximação ou busca por pedaços. `GAS` encontra `gas`; `cylinders` sozinho não representa automaticamente o termo `8 cylinders`.

## 19. As listas estão em disco ou em memória?

Há persistência em dois arquivos, com eventos de adição e remoção. Ao abrir cada arquivo, o programa reproduz esses eventos e monta o dicionário e os postings em memória. Os carros permanecem no arquivo de dados. Essa solução é simples de atualizar e consultar, mas a memória e o tempo de abertura crescem com o índice e seu histórico.

`compactar()` regrava somente os postings atuais. A troca do arquivo é atômica; se o sistema de arquivos não suportar a troca, o método falha preservando o original. CRC32 ajuda a detectar alterações acidentais dos eventos, mas não é criptografia nem autenticação contra um atacante.

## 20. Se eu escolher Hash no menu, os outros índices deixam de ser atualizados?

Não. A escolha indica o caminho usado para localizar ou confirmar a operação. Uma alteração sempre mantém árvore, hash, lista de anos e lista de características. Se só o índice escolhido fosse atualizado, consultar pelo outro método poderia devolver dados antigos.

## 21. Como o Create usa um índice se o registro ainda não existe?

O próximo ID vem do cabeçalho. Depois de gravar o carro e atualizar todos os índices, o método escolhido confirma que o novo ID aponta para a posição esperada. Ao escolher lista, essa confirmação usa o ano do carro criado. O programa não simula uma busca por registro inexistente para dizer que fez indexação.

## 22. O que muda na atualização quando o novo nome é maior?

A serialização fica com outro tamanho. O registro novo vai para o fim, a versão antiga recebe lápide e todos os índices passam a apontar para o novo endereço. O ID é preservado. Se a serialização tem exatamente o mesmo tamanho, o programa sobrescreve o conteúdo no lugar. Neste projeto, até uma redução de tamanho provoca realocação.

## 23. Mudar só o ano também exige atualizar índices?

Sim. Mesmo que o endereço e o ID permaneçam iguais, a lista do ano antigo precisa perder o posting e a do ano novo precisa recebê-lo. O coordenador remove as entradas antigas e insere as novas em todas as estruturas, mantendo um fluxo único de atualização.

Ao atualizar pela lista invertida, use os filtros antigos para localizar o carro. Depois da alteração, consulte com os valores novos.

## 24. Como saber que os índices continuam corretos?

Uma consulta bem-sucedida isolada não basta. A auditoria percorre os registros ativos, monta os pares e termos esperados e compara com os quatro índices. Ela também verifica as invariantes internas das estruturas. Os testes acrescentam cenários de divisão, remoção, realocação, reabertura e arquivos inválidos.

**Onde olhar:** `ArquivoIndexado.auditar` e as classes `Teste*`.

## 25. O que acontece se o programa falhar no meio de uma alteração?

Antes de alterar, o CRUD escreve um diário com informações para restaurar o estado anterior do arquivo de dados. Um marcador informa que há trabalho pendente nos índices. Na recuperação, o programa pode desfazer a alteração e reconstruir os índices. A base tem uma trava contra abertura simultânea por outra instância do aplicativo.

Isso não é uma promessa de suportar qualquer falha de hardware ou de oferecer transações concorrentes. A importação da base inteira também tem uma estratégia própria, com temporário e backup, diferente do diário de um único registro.

## 26. Por que não reconstruir tudo depois de cada alteração?

Porque isso exigiria percorrer toda a base a cada operação e desperdiçaria boa parte do benefício da indexação. No caminho normal, a B+ altera páginas envolvidas, o hash altera o bucket e eventualmente o diretório, e as listas acrescentam eventos dos postings afetados. A reconstrução fica para inicialização, mudança de parâmetros, recuperação ou pedido explícito.

## 27. Qual é a principal limitação de memória?

As listas mantêm dicionários e postings em memória; o hash mantém seu diretório. Consultas por listas e a auditoria também materializam resultados. Para bases muito maiores, seria preciso paginar postings e resultados e controlar melhor o cache. As páginas da árvore e os buckets do hash já são acessados pelo arquivo conforme necessário.

## 28. O que demonstrar se o professor pedir uma prova rápida?

Crie um carro, consulte seu ID pelos três métodos, altere apenas o ano e depois aumente o nome. Compare os endereços antes e depois, pesquise o novo ano com uma característica e confirme que o filtro antigo perdeu o carro. Exclua, consulte novamente pelos três métodos, reabra os arquivos e execute a auditoria. Essa sequência verifica justamente os pontos em que os índices podem ficar desatualizados.

Para a árvore, execute também seu teste de inserções e remoções; para o hash, use capacidade pequena para provocar divisões. Apresente os resultados que surgirem nessa execução, distinguindo exemplos didáticos de medições reais.
