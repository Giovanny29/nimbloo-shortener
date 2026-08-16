# Nimbloo URL Shortener

Serviço interno de **links curtos** da Nimbloo — backend construído para o teste técnico de
Engenheiro(a) de Software Júnior. Links curtos que **expirem**, sejam **rastreados** e não
vazem, com interface web mínima (React + TypeScript).

## Stack

- **Java 21** + **Spring Boot 3.3.5** (Maven)
- **DynamoDB** — persistência (DynamoDB Local via Docker)
- **Redis 7** — cache do caminho de redirect
- **SQS** (LocalStack) — registro de cliques assíncrono, com DLQ
- **React 18 + TypeScript** (Vite) — interface web servida pela própria imagem do backend
- Testes: JUnit 5 + Mockito + MockMvc (48 testes, rodam sem Docker)

## Como rodar

Pré-requisito: Docker.

```bash
docker compose up -d --build
```

Isso sobe DynamoDB, Redis, LocalStack (SQS) e a aplicação em `http://localhost:8080`. A
imagem do backend é **multi-stage**: compila o frontend (Node), empacota o jar (Maven) e
**serve a interface web e a API na mesma porta 8080** — abra `http://localhost:8080` no
navegador. O container só inicia após os serviços de infra passarem no healthcheck. A
tabela DynamoDB `urls`, a fila SQS `url-click-events` e a DLQ são criadas automaticamente
no boot.

> **Atenção:** se você já rodou uma versão anterior do projeto em que a tabela foi criada
> com a chave `shortCode`, apague a tabela (ou o volume do DynamoDB Local) antes de subir a
> versão atual — o código usa `code` como chave de partição.

## Endpoints

| Método | Rota | Comportamento |
|---|---|---|
| `POST` | `/api/v1/links` | Cria link curto. Body: `{ "url": "...", "expiresAt": "...", "alias": "..." }` (os dois últimos opcionais) |
| `GET` | `/{code}` | Redireciona (302) para a URL original |
| `GET` | `/api/v1/links` | Lista links (paginada por cursor) |
| `GET` | `/api/v1/links/{code}` | Detalhe do link + total de cliques |
| `DELETE` | `/api/v1/links/{code}` | Desativa o link (204) |

### Exemplo

```bash
curl -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/fotos-veiculo","alias":"fotos-007"}'
```

## Frontend (React + TypeScript)

Interface enxuta em `frontend/` (Vite), servida em `http://localhost:8080` pela mesma
imagem do backend. Recursos:

- Formulário de criação (URL, alias e expiração opcionais) com **botão de copiar** o link gerado.
- Listagem paginada: código, destino, cliques, status (ativo / expirado / desativado) e data de criação, com **filtro por status** e **copiar/deletar por link** (delete com confirmação em SweetAlert2 nas cores da marca).
- Estados explícitos de **loading**, **erro** e **lista vazia**, com "carregar mais" via cursor.
- Visual com as cores da marca Nimbloo (`#DD5B2A`, `#4F2463`, `#D9AAFF`...), fonte Bricolage Grotesque e favicon próprio.

Desenvolvimento local com hot reload (backend rodando em `:8080`, proxy do Vite para `/api`):

```bash
cd frontend
npm install
npm run dev   # http://localhost:5173
```

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

### Contagem de cliques assíncrona (SQS, bônus 1)
O redirect **não** atualiza o contador de forma síncrona: ele dispara uma mensagem na fila
e o consumidor `SqsClickConsumer` executa um `UPDATE ... ADD` atômico no DynamoDB. Isso
mantém a latência do redirect baixa e barata sob pico, com semântica at-least-once. A fila
principal tem **DLQ** (`url-click-events-dlq`, max 5 receives) associada no boot para que
mensagens que falhem permanentemente não reprocessem indefinidamente.

### Cache no caminho de redirect (Redis, bônus 2)
O `GET /{code}` consulta o Redis primeiro (TTL 24h). A validade do link (expirado/
desativado) é **recalculada a cada requisição** a partir do item (cache ou banco), portanto
o TTL do cache não mascara expiração. A desativação invalida o cache imediatamente.

### Dupla camada de validação
Bean Validation (`@NotBlank`, `@Pattern`, `@Future`) no DTO + validação explícita no
service (esquema HTTP/HTTPS, host presente, data no passado). A camada do service protege
contra chamadas programáticas e centraliza mensagens em português.

### Rotas de redirect convivem com a SPA
O `GET /{code}` é restrito ao charset real dos códigos (`[a-zA-Z0-9_-]{3,30}`). Sem isso, o
pattern capturaria também `index.html`/`favicon.ico`/assets de um segmento — inclusive o
`forward` interno do welcome page do Spring — e quebraria a entrega da interface web. A
restrição não afeta links reais (códigos Base62 têm 7+ chars; aliases têm 3–30).

## Limitações conhecidas (identificadas, ainda não corrigidas)

1. **Listagem via Scan** — sem índice secundário, não há ordenação por data de criação e o
   custo de leitura cresce com o volume. Correção planejada: GSI por `created_at`.
2. **Contagem de cliques com semântica at-least-once** — o SQS pode entregar a mesma
   mensagem mais de uma vez, então a contagem pode ocasionalmente superestimar cliques.
   Aceitável para métricas internas.
3. **Itens expirados permanecem na tabela** — a expiração é lógica (status `EXPIRED`); o
   item só sai da listagem com desativação manual. Correção planejada: TTL nativo do
   DynamoDB.

## O que eu faria com mais uma semana

1. GSI por `created_at` para listagem ordenada e mais barata; TTL nativo do DynamoDB para
   expiração real.
2. **Multi-tenant (bônus 3)**: API keys por cliente, isolando leitura/escrita por tenant.
3. **Métricas (bônus 5)**: Actuator/Micrometer para latência do redirect e taxa de 404.
4. **Testes de integração** com Testcontainers (DynamoDB Local + Redis reais) cobrindo a
   camada de repositório e o fluxo SQS ponta a ponta, incluindo a DLQ.
5. Deploy em uma nuvem (bônus 6) com o link no README.

## Uso de IA — declaração

Conforme pedido no enunciado, segue o que foi gerado com auxílio de IA e o que foi escrito
por mim:

- **Configuração inicial AWS (SQS/DynamoDB), docker-compose, entidade, repositório,
  service e controllers**: escritos por mim com revisão de IA (assistente de código no
  editor), que apontou inconsistências e boas práticas do Spring Cloud AWS.
- **Correção de falha real**: a IA **identificou** (antes de eu rodar) o desalinhamento
  entre a chave `shortCode` usada na criação da tabela e a chave `code` usada pela
  entidade — o erro "One of the required keys was not given a value". Unifiquei o nome da
  chave com base no diagnóstico.
- **Testes**: os **testes foram escritos por mim** com auxílio da IA na **configuração do
  Mockito** (mocks de `StringRedisTemplate`/`SqsTemplate`, `@ExtendWith(MockitoExtension)`)
  e na estruturação dos casos. As asserções, os cenários de erro e a decisão de testar com
  mocks (sem depender de infra Docker) foram escolhas minhas.
- **README**: escrito por mim, revisado com IA para estrutura.

Nada foi omitido de propósito; se algo foi gerado por IA, está descrito acima e consigo
defender cada linha na entrevista.