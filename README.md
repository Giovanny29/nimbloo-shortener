# Nimbloo URL Shortener

Serviço interno de **links curtos** da Nimbloo — backend construído para o teste técnico de
Engenheiro(a) de Software Júnior. Links curtos que **expirem**, sejam **rastreados** e não
vazem, com interface web mínima (frontend em desenvolvimento).

## Stack

- **Java 21** + **Spring Boot 3.3.5** (Maven)
- **DynamoDB** — persistência (DynamoDB Local via Docker)
- **Redis 7** — cache do caminho de redirect + gerador de IDs atômicos (`INCR`)
- **SQS** (LocalStack) — registro de cliques assíncrono
- Testes: JUnit 5 + Mockito + MockMvc (43 testes)

## Como rodar

Pré-requisitos: Docker + Java 21.

```bash
# 1. Sobe a infraestrutura (DynamoDB, Redis, LocalStack)
docker compose up -d

# 2. Sobe a aplicação
cd backend
./mvnw spring-boot:run
```

A aplicação sobe em `http://localhost:8080`. A tabela DynamoDB `urls` e a fila SQS
`url-click-events` são criadas automaticamente no primeiro boot.

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

## Decisões e trade-offs

### Tamanho do código: Base62 + Redis `INCR` + scramble de Knuth
Em vez de gerar código aleatório e verificar colisão no banco (round-trip extra por link
criado), usamos um contador atômico no Redis (`INCR`) e codificamos em Base62 com um
scramble multiplicativo para ofuscar a sequência. O custo: se o Redis for perdido, o
contador pode ser resetado e gerar códigos que já existem no DynamoDB (ver Limitações).
Os códigos têm **tamanho mínimo de 7 caracteres**, mas crescem com o contador — não são
fixos.

### Modelagem de dados (DynamoDB)
Tabela única `urls` com `code` como chave de partição. O item guarda URL original, contagem
de cliques, flag `active` e datas em ISO-8601 (string). Optamos por **desativação lógica**
(DELETE == `active=false`), o que mantém as métricas históricas do link. A listagem usa
`Scan` com paginação por cursor — aceitável para uma ferramenta interna, mas O(n) em custo
(ver Limitações).

### Contagem de cliques assíncrona (SQS, bônus 1)
O redirect **não** atualiza o contador de forma síncrona: ele dispara uma mensagem na fila
e o consumidor `SqsClickConsumer` executa um `UPDATE ... ADD` atômico no DynamoDB. Isso
mantém a latência do redirect baixa e barata sob pico, com semântica at-least-once.

### Cache no caminho de redirect (Redis, bônus 2)
O `GET /{code}` consulta o Redis primeiro (TTL 24h). A validade do link (expirado/
desativado) é **recalculada a cada requisição** a partir do item (cache ou banco), portanto
o TTL do cache não mascara expiração. A desativação invalida o cache imediatamente.

### Dupla camada de validação
Bean Validation (`@NotBlank`, `@Pattern`, `@Future`) no DTO + validação explícita no
service (esquema HTTP/HTTPS, host presente, data no passado). A camada do service protege
contra chamadas programáticas e centraliza mensagens em português.

## Limitações conhecidas (identificadas, ainda não corrigidas)

1. **Corrida no alias** — `existsByCode` seguido de `putItem` não é atômico: dois POSTs
   simultâneos com o mesmo alias podem passar na checagem e o segundo sobrescrever o
   primeiro. Correção planejada: `putItem` condicional com `if_not_exists` no DynamoDB.
2. **Código gerado pode colidir com alias** — o código Base62 gerado não verifica
   `existsByCode`; um usuário que criar o alias `0000000` (ou outro futuro) pode ser
   sobrescrito pelo gerador.
3. **Listagem via Scan** — sem índice secundário, não há ordenação por criação e o custo
   cresce com o volume. Correção planejada: GSI por `created_at` (e, idealmente, TTL
   nativo do DynamoDB para expiração).
4. **Redis `INCR` não persistente** — reset/eviction do Redis pode reutilizar IDs e gerar
   códigos duplicados no DynamoDB.
5. **SQS sem DLQ** — mensagens que falham permanentemente reprocessam indefinidamente.
6. **`pageSize` sem teto** — um cliente pode pedir páginas gigantes e amplificar custo.
7. **`LinkMetricsResponse` sem uso** — DTO criado para a rota de métricas, que não existe
   no escopo atual; código morto.
8. **Frontend ainda não implementado** — próximo passo após fechar os itens acima.

## O que eu faria com mais uma semana

1. Corrigir as limitações acima (alias atômico, GSI, DLQ, teto de `pageSize`, unicidade do
   código gerado).
2. **Multi-tenant (bônus 3)**: API keys por cliente, isolando leitura/escrita por tenant.
3. **Métricas (bônus 5)**: Actuator/Micrometer para latência do redirect e taxa de 404.
4. **Testes de integração** com Testcontainers (DynamoDB Local + Redis reais) cobrindo a
   camada de repositório e o fluxo SQS ponta a ponta.
5. **Dockerfile do backend** para que `docker compose up` suba a aplicação completa.
6. **Frontend** em React + TypeScript (Vite): formulário com copiar, listagem com
   loading/erro/lista vazia.

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