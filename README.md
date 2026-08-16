# Nimbloo URL Shortener

Serviço interno de **links curtos** da Nimbloo — teste técnico de Engenheiro(a) de Software
Júnior. Links curtos que **expirem**, sejam **rastreados** e não vazem, com interface web
mínima (React + TypeScript).

- Requisitos do enunciado: **todos os obrigatórios atendidos** + 2 dos bônus opcionais
  (SQS assíncrono com DLQ e cache Redis no redirect), dentro do limite de "no máximo 2".
- **53 testes automatizados** (rodam sem Docker) — feliz + erros: 400, 404, 409.
- Auditado contra o enunciado na seção [Requisitos](#requisitos-do-enunciado--checklist-de-auditoria).

---

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.3.5, Maven (wrapper) |
| Banco | DynamoDB (`DynamoDB Local` via Docker) |
| Cache | Redis 7 (caminho de redirect) |
| Mensageria | SQS (`LocalStack`) + DLQ |
| Frontend | React 18 + TypeScript + Vite 5 + `sweetalert2` |
| Testes | JUnit 5, Mockito, MockMvc — **53 testes, sem Docker** |

---

## Como rodar

Pré-requisito: **Docker Desktop** rodando.

```bash
docker compose up -d --build
```

Isso sobe DynamoDB, Redis, LocalStack (SQS) e a aplicação em `http://localhost:8080`.
A imagem do backend é **multi-stage** (Node compila o frontend → Maven empacota o jar →
JRE serve tudo), então **interface web e API ficam na mesma porta 8080**. O container só
inicia depois que os serviços de infra passam no healthcheck (`depends_on: condition:
service_healthy`); no boot, o app **cria/verifica** a tabela DynamoDB `urls`, as filas
`url-click-events` e `url-click-events-dlq` (com redrive policy de 5 receives) — tudo
idempotente.

Após subir:

- Interface web: http://localhost:8080
- API: http://localhost:8080/api/v1/links
- DynamoDB Local: http://localhost:8000 · Redis: localhost:6379 · SQS: localhost:4566

### Testes (sem Docker)

```bash
cd backend
.\mvnw.cmd test        # ou: ./mvnw test
```

### Desenvolvimento do frontend (hot reload)

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173 — proxy de /api para :8080
```

### Troubleshooting (erros reais que já encontramos)

| Sintoma | Causa | Correção |
|---|---|---|
| Tabela criada com chave errada (erro "One of the required keys was not given a value") | Versão antiga usava `shortCode` como partition key | Apague a tabela (ou o volume do DynamoDB Local) e suba de novo — o boot cria com a chave `code` |
| Porta 8080 ocupada no Windows | Outro processo escutando (wslrelay/com.docker.backend são do próprio Docker Desktop) | Descubra com `netstat -ano \| findstr 8080`; se for Docker/wslrelay, é instância antiga do backend — pare com `docker compose down` |
| `docker compose up` "não sobe" sem erro | Docker Desktop fechado | Inicie o Docker Desktop e rode de novo (healthchecks + depends_on forçam ordem correta) |
| API responde, web não | Limpeza de cache/conteúdo dist de build anterior | `docker compose up -d --build` força rebuild dos 3 estágios |
| Arquivos duplicados de build no repositório | `frontend/frontend/...` (pasta criada por engano) | Apague manualmente e use o `.dockerignore` raiz (que já cobre node_modules, dist, target) |

---

## Endpoints

| Método | Rota | Comportamento |
|---|---|---|
| `POST` | `/api/v1/links` | Cria link curto. Body: `{ "url": "...", "expiresAt": "...", "alias": "..." }` (últimos dois opcionais) → `201` |
| `GET` | `/{code}` | Redireciona (302) para a URL original |
| `GET` | `/api/v1/links` | Lista links (paginada por cursor; `pageSize` 1–100, default 10) |
| `GET` | `/api/v1/links/{code}` | Detalhe do link + total de cliques |
| `DELETE` | `/api/v1/links/{code}` | Desativa o link (204, desativação lógica) |

Erros: `400` validação (com `fieldErrors` por campo), `404` link/não encontrado ou
expirado/desativado, `409` alias já em uso — corpo consistente via `GlobalExceptionHandler`.

### Exemplo

```bash
curl -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/fotos-veiculo","alias":"fotos-007"}'
```

```bash
curl "http://localhost:8080/api/v1/links?pageSize=5"        # 1ª página (sem lastKey)
curl "http://localhost:8080/api/v1/links?pageSize=5&lastKey=<cursor-da-resposta>"
```

---

## Backend em detalhe

### Arquitetura em camadas

`Controller` → `LinkService` → `UrlItemRepository` → DynamoDB/SQS/Redis. Erros viram HTTP
status em um único `GlobalExceptionHandler`. O repositório usa **dois clientes** do
DynamoDB de propósito: o **enhanced client** (mapeamento objeto↔item, CRUD tipado) e o
**low-level client** (operações atômicas e de controle fino) — detalhe na seção
[Decisões](#decisões-e-trade-offs).

### Fluxo de criação de link (sem alias)

1. Validação de URL (scheme http/https + host real via `URI`) e expiração (data futura).
2. `incrementIdCounter()`: `UPDATE ... ADD id_counter :inc` **atômico** num item interno
   `__counter__` (low-level client, `returnValues=UPDATED_NEW`).
3. ID → Base62 (mín. 7 chars) → scramble multiplicativo de Knuth (ofusca a sequência).
4. `putItem` **condicional** `attribute_not_exists(code)` — se falhar (alias já existe),
   incrementa de novo e tenta até 10x; depois disso, 409.
5. Grava no Redis (TTL 24h) e responde 201 com `shortUrl`.

### Fluxo de redirect (`GET /{code}`)

1. Regex do controller garante que só códigos reais chegam aqui (`[a-zA-Z0-9_-]{3,30}`).
2. Redis primeiro (`StringRedisTemplate`, JSON do `UrlItem`) → miss vai ao DynamoDB.
3. Status (ativo/expirado/desativado) **recalculado a cada requisição** — cache não
   mascarar expiração.
4. Ativo → 302 com `Location`. Expirado/desativado → 404 (sem vazar existência).
5. Clique disparado **assíncrono** pro SQS (fire-and-forget, loga erro se falhar).

### Contagem de cliques (SQS + DLQ)

`SqsClickConsumer` escuta `url-click-events` e executa `UPDATE ... ADD click_count :inc`
atômico. Semântica **at-least-once** (pode superestimar levemente — aceito). Falhas
permanentes caem na DLQ após 5 receives. **Falha parcial assumida:** se a fila parar, o
redirect segue 100% de pé e cliques somem da contagem até o serviço voltar.

### Listagem paginada (cursor)

DynamoDB não tem OFFSET: `Scan` + `exclusiveStartKey`. Primeira página **não manda
cursor** (ausência = começa do início); as seguintes mandam o `lastKey` da resposta
anterior. O builder é montado condicionalmente — `exclusiveStartKey` só é anexado quando
existe cursor. `limit` conta antes do filtro (`code <> :counter`), por isso uma página
pode voltar com 9 itens. Sem total de páginas nem salto direto (ver Decisões).

### Inicialização de infraestrutura

`AwsResourceInitializer` (no `ApplicationReadyEvent`) cria/verifica a tabela (PAY_PER_REQUEST),
a fila e a DLQ com `RedrivePolicy` — idempotente, roda em todo boot.

---

## Frontend em detalhe (React + TypeScript)

Interface enxuta em `frontend/`, servida na mesma imagem do backend. Recursos:

- **Formulário de criação**: URL, alias e expiração opcionais, com **copiar** o link gerado.
  Botão desabilitado durante a criação.
  - Validação **client-side** (URL vazia/inválida, data passada → mensagem imediata) e
    **server-side** (erros de campo do backend aparecem sob o campo específico; erros
    globais/409 viram alerta).
- **Listagem**: código (link clicável), destino, cliques, status (ativo/expirado/desativado),
  data de criação, **filtro por status** e **copiar/deletar por linha**.
  - Delete com confirmação **SweetAlert2** nas cores da marca (Sim laranja `#DD5B2A` /
    Não roxo `#784495`) e feedback de sucesso/erro pós-operação.
  - Link desativado → botão delete cinza desabilitado com tooltip "Link já desativado".
  - "Carregar mais" via cursor (estado `lastKey`; primeira página não envia nada).
- **Estados explícitos**: loading (criação e listagem), erro (com botão de tentar de novo)
  e **lista vazia** (CTA para criar o primeiro link).
- Visual com as cores da marca (`#DD5B2A`, `#4F2463`, `#784495`, `#D9AAFF`...), fonte
  **Bricolage Grotesque** (Google Fonts) e favicon próprio.
  - Detalhe técnico do Vite: favicon em `public/` (copiado verbatim, referenciado por
    URL) e logo em `src/assets/` (importado no bundle com hash de build) — os dois
    mecanismos corretos, cada um no seu lugar.

---

## Decisões e trade-offs

### Tamanho do código: Base62 + contador atômico no DynamoDB + scramble de Knuth
Em vez de gerar código aleatório e verificar colisão no banco (round-trip extra por link
criado), usamos um **contador atômico no DynamoDB** (`UPDATE ... ADD` em um item interno
`__counter__`) que nunca se perde com flush de cache. O valor é codificado em Base62 com um
scramble multiplicativo para ofuscar a sequência. A gravação é condicional
(`attribute_not_exists(code)`): se o código já existir (colisão com alias ou retry), o
serviço incrementa e tenta novamente (até 10 tentativas). Os códigos têm **tamanho mínimo
de 7 caracteres**, mas crescem com o contador — não são fixos.

### Modelagem de dados (DynamoDB)
Tabela única `urls` com `code` como chave de partição. O item guarda URL original, contagem
de cliques, flag `active` e datas em ISO-8601 (string). Optamos por **desativação lógica**
(DELETE == `active=false`), o que mantém as métricas históricas do link. A listagem usa
`Scan` com paginação por cursor — aceitável para uma ferramenta interna, mas O(n) em custo
(ver Limitações).

### Dois clientes do DynamoDB (enhanced + low-level) — e por quê
O **enhanced client** (reflexão só na criação do `TableSchema`, depois mapeamento direto)
dá CRUD tipado do `UrlItem`. O **low-level client** aparece exatamente onde o ganho é
real: `UPDATE ... ADD` **atômico** (contador de IDs e de cliques — porta de entrada para
corrida) e o `exclusiveStartKey` da paginação. Forçar tudo num cliente só custaria
expressões string ou mapeamento manual em todo lugar; o resultado é cada trecho escrito do
jeito mais direto para a operação.

### Cache Redis no caminho do redirect — controle de baixo nível
Cache manual com `StringRedisTemplate` (chave `link:{code}`, valor = JSON do `UrlItem`,
TTL 24h, invalidação explícita no disable). Escolhemos o template de **strings** em vez
de `@Cacheable`/RedisTemplate de objeto: sem AOP nem cache manager intermediário, você vê
exatamente o que vai para o Redis. **Redis é acelerador, não fonte de verdade**: exceções
são logadas e engolidas (redirect segue via DynamoDB) e o status de expiração é recalculado
a cada requisição, então TTL nunca mascara expiração.

### Contagem de cliques assíncrona (SQS, bônus 1)
O redirect **não** atualiza o contador de forma síncrona: ele dispara uma mensagem na fila
e o consumidor `SqsClickConsumer` executa um `UPDATE ... ADD` atômico no DynamoDB. Isso
mantém a latência do redirect baixa e barata sob pico, com semântica at-least-once. A fila
principal tem **DLQ** (`url-click-events-dlq`, max 5 receives) associada no boot para que
mensagens que falhem permanentemente não reprocessem indefinidamente. Trade-off assumido:
outage da fila = cliques não contabilizados temporariamente (redirect 100% de pé).

### Dupla camada de validação
Bean Validation (`@NotBlank`, `@Pattern`, `@Size(max=2048)`, `@Future`) no DTO +
validação explícita no service (esquema HTTP/HTTPS, host presente via `URI`, data no
passado). A camada do service protege contra chamadas programáticas e centraliza mensagens
em português.

### Proteção do código reservado `__counter__`
Descoberto em teste ao vivo: um `DELETE /api/v1/links/__counter__` faria o `putItem` do
disable **substituir** o item do contador (zerando `id_counter`). A guarda no `LinkService`
devolve 404/409 **genéricos** para qualquer acesso ao código reservado — sem revelar a
existência da chave interna (não queremos ensinar o caminho do contador).

### Rotas de redirect convivem com a SPA
O `GET /{code}` é restrito ao charset real dos códigos (`[a-zA-Z0-9_-]{3,30}`). Sem isso, o
pattern capturaria também `index.html`/`favicon.ico`/assets de um segmento — inclusive o
`forward` interno do welcome page do Spring — e quebraria a entrega da interface web (bug
real encontrado e corrigido, ver "Erros reais"). A restrição não afeta links legítimos:
códigos Base62 têm 7+ chars; aliases têm 3–30 no mesmo charset.

### Paginação por cursor em vez de páginas numeradas
Sem OFFSET no DynamoDB, a página "numerada" seria O(n²) (re-scan do começo até a página N
a cada clique). Cursor espelha a mecânica do banco, é estável sob concorrência (não
desloca com inserções no meio) e o custo assume a forma O(n) do Scan — documentado em
Limitações. Trade-off: não há total de páginas nem salto direto para a página 5; é
"carregar mais", não "ir para a página 3".

### Um artefato só (backend + frontend na mesma imagem)
`docker compose up` + uma porta evita CORS, web server extra e passos de deploy na
avaliação. Em dev, o Vite proxya `/api` para `:8080` (sem mudar nada no backend).

---

## Erros reais encontrados durante o desenvolvimento (e como resolveram)

1. **Chave `shortCode` vs `code`** — a primeira versão criava a tabela com `shortCode` e a
   entidade lia `code`; resultado: "One of the required keys was not given a value" em
   toda operação. Diagnóstico de IA apontou o desalinhamento antes da execução; unifiquei
   a chave em `code` (e o README avisa quem tiver tabela antiga).
2. **Welcome page devorado pelo `/{code}`** — `GET /` faz `forward:/index.html`, que caia
   no handler genérico → 404 "código: index.html" e a web não carregava. Diagnóstico com
   logs TRACE do Spring MVC em container de debug; correção: regex no `@GetMapping` (ver
   Decisões) + teste de regressão.
3. **`DELETE` no item `__counter__`** — substituiria o contador e quebraria a geração de
   códigos. Correção: guarda `ensureNotReserved` com 404/409 genéricos + 4 testes.
4. **Favicon 404** — mover o arquivo para `public/` do Vite exige o caminho certo; uma
   tentativa criou `frontend/frontend/` vazia (commit com pasta-fantasma removida depois).
5. **Pom duplicado** — `backend/shortener/shortener/pom.xml` apareceu no histórico (commit
   junk); removido do tracking com `git rm`.
6. **Porta 8080 "ocupada"** — diagnóstico: processos `wslrelay`/`com.docker.backend` (do
   próprio Docker Desktop) escutando; não era serviço um serviço sombra local.

---

## Limitações conhecidas (identificadas, ainda não corrigidas)

1. **Listagem via Scan** — sem índice secundário, não há ordenação por data de criação e o
   custo de leitura cresce com o volume (cada página re-scanneia do cursor; O(n) por
   página). Correção planejada: GSI por `created_at` + `query`.
2. **Contagem de cliques com semântica at-least-once** — o SQS pode entregar a mesma
   mensagem mais de uma vez, então a contagem pode ocasionalmente superestimar cliques.
   Aceitável para métricas internas.
3. **Itens expirados permanecem na tabela** — a expiração é lógica (status `EXPIRED`); o
   item só sai da listagem com desativação manual. Correção planejada: TTL nativo do
   DynamoDB.
4. **Filtro de status no frontend é client-side** — suficiente para uma página de 10
   itens; em escala viraria parâmetro da API + GSI.
5. **Contador global é um único item** — serializa criações em escala muito alta
   (mitigação: shards no contador/faixa de IDs por instância). Irrelevante no volume do
   teste técnico.

---

## O que eu faria com mais uma semana

1. GSI por `created_at` para listagem ordenada e mais barata; TTL nativo do DynamoDB para
   expiração real.
2. **Multi-tenant (bônus 3)**: API keys por cliente, isolando leitura/escrita por tenant.
3. **Métricas (bônus 5)**: Actuator/Micrometer para latência do redirect e taxa de 404.
4. **Testes de integração** com Testcontainers (DynamoDB Local + Redis reais) cobrindo a
   camada de repositório e o fluxo SQS ponta a ponta, incluindo a DLQ.
5. Deploy em uma nuvem (bônus 6) com o link no README.

---

## Requisitos do enunciado — checklist de auditoria

| Requisito | Status |
|---|---|
| `POST /api/v1/links` (alias/expiração opcionais) | ✓ |
| `GET /{code}` redireciona | ✓ (302, expirado/desativado → 404) |
| `GET /api/v1/links` paginada | ✓ (cursor) |
| `GET /api/v1/links/{code}` detalhe + cliques | ✓ |
| `DELETE /api/v1/links/{code}` desativa | ✓ (204, desativação lógica) |
| Java 21 + Spring Boot 3.3.5 | ✓ |
| DynamoDB (Local) | ✓ |
| Validação: URL malformada / esquema / data passada | ✓ (DTO + service) |
| Expiração/desativação não redireciona | ✓ (404) |
| Contagem de cliques | ✓ (SQS assíncrono) |
| Testes: feliz + ≥2 erros | ✓ 53 testes (400, 404, 409) |
| `docker compose up` sobe tudo | ✓ (validado ao vivo) |
| Formulário + copiar o link | ✓ |
| Listagem: código, destino, cliques, status, criação | ✓ |
| Loading, erro, lista vazia explícitos | ✓ |
| React + TypeScript (Vite) | ✓ |
| Bônus 1 (SQS + DLQ) e bônus 2 (Redis) — máx. 2 | ✓ |

---

## Como a IA me ajudou a configurar o projeto

Este projeto foi desenvolvido com assistência de IA (assistente de código no editor —
opencode). Abaixo, de forma organizada, **o que a IA fez e o que foi decidido por mim**.
Posso defender cada linha na entrevista.

### Baixo nível do DynamoDB (o que aprendi de verdade)
- **Dois clientes na mesma tabela**: a IA me apresentou a divisão enhanced client (CRUD
  tipado do `UrlItem`, mapeamento objeto↔item) vs. low-level client (`DynamoDbClient`).
  Usamos o low-level exatamente onde a operação é atômica ou de controle fino:
  `UpdateItemRequest` com `ADD` (contador de IDs e de cliques) e `exclusiveStartKey` na
  paginação. A IA explicou por que o enhanced client não cobre esses casos com elegância
  (expressões enjauladas no modelo de objetos) e me mostrou o `.builder()` de cada
  requisição linha por linha.
- **`UPDATE ... ADD` atômico**: a IA explicou a diferença entre "lê → soma → grava"
  (perde corrida) e a operação atômica do DynamoDB — foi a base do contador de IDs que
  nunca se perde com flush de cache.
- **`putItem` condicional**: `attribute_not_exists(code)` com tratamento de
  `ConditionalCheckFailedException` para fechar a corrida de alias — e a matemática de
  retry até 10 tentativas.
- **Paginação com Scan**: entendi que DynamoDB não tem OFFSET e que o `lastEvaluatedKey`
  da resposta é o cursor da *próxima* chamada — daí o `if (lastEvaluatedKey != null &&
  !lastEvaluatedKey.isBlank())` que anexa o `exclusiveStartKey` só quando existe.
- **Debug de verdade**: erros como "One of the required keys was not given a value"
  (chave `shortCode` vs `code`) foram apontados pela IA, mas a correção foi negociada e
  aplicada por mim.

### Baixo nível do Redis (StringRedisTemplate manual)
- **Por que não `@Cacheable`**: a IA me mostrou que uma config de cache manager tinha
  sido adicionada sem uso real (`sem cache manager ativado = infra para nada`) e que
  anotação esconderia o que eu preciso controlar: TTL, invalidação imediata no disable e
  falha tolerada. Removi a config (branch `fix/redis-config`) e passei a usar
  `StringRedisTemplate` direto.
- **O que é o template de strings**: `opsForValue().get/set/delete` com JSON do
  `UrlItem` via `ObjectMapper` — a IA explicou que no Redis "baixo nível" está a
  simplicidade: sem conversor, sem cache manager, dá para ver exatamente o que vai para a
  chave. O TTL de 24h e o recálculo de status a cada leitura (para o TTL não mascarar
  expiração) foram decisões em conjunto.

### Padrão Builder (a maior barreira inicial)
- **O que é**: a IA me explicou o padrão como "construtor separado da construção": você
  monta a configuração passo a passo e o `build()` congela o objeto — usado pelos SDKs
  AWS porque suas APIs são complexas e imutáveis.
- **Por que os SDKs AWS só aceitam builder**: sem construtores gigantes cheios de nulos,
  sem objeto parcialmente montado; o builder valida e o objeto nasce pronto para uso.
- **Onde está no projeto**: `ScanEnhancedRequest.builder().limit().filterExpression()`
  (e o `exclusiveStartKey` **condicional** — builder variando por `if` antes do `build()`);
  `PutItemEnhancedRequest.builder(UrlItem.class).item().conditionExpression().build()`;
  `UpdateItemRequest.builder().tableName().key().updateExpression().returnValues()`;
  `CreateTableRequest.builder()`, `GetQueueUrlRequest.builder()` e até `ResponseEntity.
  status().header().build()` no controller.
- **Quando não usar**: a IA me fez notar que o `UrlItem` é mutável (setters) porque o
  mapper do enhanced client exige bean com getters/setters — builder é para entrada
  imutável (requisições), não para entidades que mudam no ciclo de vida (como
  `setActive(false)` no disable).

### Testes
- Estruturação do Mockito (`@ExtendWith(MockitoExtension)`, mocks de
  `StringRedisTemplate`/`SqsTemplate`) e dos caminhos de MockMvc standalone. As asserções,
  os cenários de erro (400/404/409, corrida de alias, colisão do put condicional, guarda
  do `__counter__`) e a decisão de testar com mocks (sem depender de infra Docker) foram
  escolhas minhas.

### Frontend e operação
- Estruturação inicial do Vite (proxy `/api`, multi-stage do Docker) e revisão das
  features que eu especifiquei; eu escrevi o comportamento (estados de loading/erro/vazio,
  filtro por status, SweetAlert2 com cores da marca, copiar com fallback, erros de campo
  do backend).

### O que foi escrito por mim (não-IA)
Listagem de features do frontend, escolha dos bônus (1 e 2, não 3/4), decisões de design
de produto (404 para expirado, desativação lógica, mensagens em português), a proteção do
`__counter__` (idéia minha após o bug ser diagnosticado pela IA) e este README.

---

## Uso de IA — declaração (obrigatório pelo enunciado)

Conforme o item 5 do enunciado: este projeto foi construído com assistência de IA,
detalhada na seção acima. Nada foi omitido de propósito; se algo foi gerado por IA, está
descrito ali, e consigo explicar e defender cada linha na entrevista.