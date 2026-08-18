# Nimbloo URL Shortener

Serviço interno de **links curtos** da Nimbloo — teste técnico de Engenheiro(a) de Software
Júnior. Links curtos que **expirem**, sejam **rastreados** e não vazem, com interface web
mínima (React + TypeScript).

- Requisitos do enunciado: **todos os obrigatórios atendidos** + 2 dos bônus opcionais
  (SQS assíncrono com DLQ e cache Redis no redirect), dentro do limite de "no máximo 2".
- **60 testes automatizados** — 57 unitários (rodam sem Docker) + 3 de integração com
  serviços reais (DynamoDB Local, Redis e SQS via Testcontainers).
- Auditado contra o enunciado na seção [Requisitos](#requisitos-do-enunciado--checklist-de-auditoria).

---

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.3.5, Maven (wrapper) |
| Banco | DynamoDB (`DynamoDB Local` via Docker) |
| Cache | Redis 7 (caminho de redirect) |
| Mensageria | SQS (`LocalStack`) + DLQ |
| Frontend | React 18 + TypeScript + Vite 7 + `sweetalert2` |
| Testes | JUnit 5, Mockito, MockMvc (57 unitários) + Testcontainers (3 de integração) |

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

### Testes

```bash
cd backend
.\mvnw.cmd test        # ou: ./mvnw test
```

- **57 unitários** (service, controller via MockMvc, status, encoder, contexto) — rodam em
  segundos, sem Docker.
- **3 de integração** (`LinkStackIntegrationTest`, Testcontainers): DynamoDB Local, Redis e
  LocalStack (SQS) **reais** — cobrem o fluxo completo create → redirect → clique via SQS
  → disable (com contador preservado), o disable atômico sob concorrência e o fallback do
  redirect com o Redis **derrubado de verdade**. Se o Docker não estiver disponível, a
  suíte é pulada (não quebra o build); com Docker, roda junto no `test`.

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
5. Grava no Redis (TTL 5 min) e responde 201 com `shortUrl`.

### Fluxo de redirect (`GET /{code}`)

1. Regex do controller garante que só códigos reais chegam aqui (`[a-zA-Z0-9_-]{3,30}`).
2. Redis primeiro (`StringRedisTemplate`, JSON do `UrlItem`) → miss vai ao DynamoDB.
3. Status (ativo/expirado/desativado) **recalculado a cada requisição** — cache não
   mascarar expiração.
4. Ativo → 302 com `Location`. Expirado/desativado → 404 (sem vazar existência).
5. Clique disparado **assíncrono** pro SQS: o envio roda numa thread própria
   (`sqs-dispatcher`), nunca no request thread — fire-and-forget, loga erro se falhar.

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
TTL 5 min). Escolhemos o template de **strings** em vez de `@Cacheable`/RedisTemplate de
objeto: sem AOP nem cache manager intermediário, você vê exatamente o que vai para o
Redis. **Redis é acelerador, não fonte de verdade**: qualquer exceção é logada e engolida
(redirect segue via DynamoDB — validado por teste de integração que derruba o Redis), e o
status é recalculado a cada requisição, então TTL nunca mascara expiração. No **disable**,
em vez de deletar a chave (o que abria corrida com um redirect em voo repopulando a entrada
antiga), reescrevemos a entrada com o estado real `active=false` (tombstone) — o redirect
seguinte lê 404. Caso residual: Redis fora **exatamente durante** o disable → a entrada
antiga pode sobreviver até o TTL de 5 min, teto documentado.

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
4. **Teste de integração do fluxo DLQ** — o fluxo de clique ponta a ponta já é coberto
   pelo Testcontainers; falta o cenário de falha permanente (5 receives → DLQ).
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
| Testes: feliz + ≥2 erros | ✓ 60 testes (57 unitários + 3 integração reais) |
| `docker compose up` sobe tudo | ✓ (validado ao vivo) |
| Formulário + copiar o link | ✓ |
| Listagem: código, destino, cliques, status, criação | ✓ |
| Loading, erro, lista vazia explícitos | ✓ |
| React + TypeScript (Vite) | ✓ |
| Bônus 1 (SQS + DLQ) e bônus 2 (Redis) — máx. 2 | ✓ |

---

## Como a IA me auxiliou 

O **núcleo do projeto é de minha autoria**: a arquitetura de códigos curtos **Base62 +
contador atômico no DynamoDB** (com scramble de Knuth), as **validações em duas camadas**,
a modelagem da tabela, a desativação lógica, o padrão de 404 para expirados, a escolha
dos bônus (SQS + Redis), os testes e todas as features do frontend foram **idealizados,
escritos e são defendidos por mim**. A IA não decidiu arquitetura, não escreveu regra de
negócio e não escolheu os bônus.

Conforme o item 5 do enunciado, a IA me auxiliou em **pontos pontuais**:

1. **Entender o padrão Builder** dos SDKs AWS — por que essas APIs são builder-only e
   como montar requisições condicionalmente (ex.: o `exclusiveStartKey` que só é anexado
   quando existe cursor, antes do `build()`). Eu apliquei no código.
2. **Conhecer as bibliotecas AWS no nível baixo** — a diferença entre o enhanced client
   e o low-level client do DynamoDB (expressão `UPDATE ... ADD` atômica) e o
   `StringRedisTemplate` direto em vez de `@Cacheable`. A aplicação no código é minha.
3. **Diagnóstico de um bug** — apontou o desalinhamento da chave `shortCode` vs `code`;
   a correção foi aplicada por mim.
4. **Configuração da suíte de testes** — apoio na estruturação do Mockito/MockMvc; os
   cenários, asserções e a decisão de testar com mocks foram meus. Depois de uma review
   externa apontar que os bugs viviam exatamente nos limites mockados, implementei a
   suíte de integração com Testcontainers (serviços reais) — decisão minha, com apoio
   pontual da IA na montagem dos contêineres.
