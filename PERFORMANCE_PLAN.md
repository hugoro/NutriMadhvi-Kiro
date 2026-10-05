# NutriMadhvi — Plano de Performance (instruções para execução)

Objetivo: aplicar melhorias de performance em ordem de impacto, **sem perda de dados** e com
**incremento automático do versionCode a cada build** (já implementado em
`app/build.gradle.kts` via `BumpVersionCodeTask` — só confirmar no log do build).

---

## Regras de segurança (OBRIGATÓRIO em TODAS as tarefas)

1. **Antes de começar**: `git status` limpo; senão, commit checkpoint
   (padrão do repo: branch `checkpoint/pre-<tema>` ou commit `CHECKPOINT: pre-<tema>`).
2. **Se a tarefa toca em dados/schema**: copiar a BD do dispositivo/emulador
   (`adb pull ... chat.db`) para `nutrimadhvi_backup_<YYYYMMDD_HHMM>_<motivo>/`
   (padrão já usado na root do repo).
3. **Migrações Room**: sempre `Migration` explícita com SQL — **nunca**
   `fallbackToDestructiveMigration`. Exportar schema (`exportSchema=true` já ativo)
   e adicionar caso ao `DatabaseMigrationTest` que prove preservação de dados.
4. **Depois de cada tarefa**:
   - `./gradlew testDebugUnitTest` → só commitar se verde;
   - `./gradlew assembleDebug` → confirmar no log `versionCode bumped: N → N+1`;
   - instalar e smoke-test manual (chat a abrir, streaming a correr, histórico a carregar).
5. **Rollback**: cada tarefa é independente; reverting o commit devolve o estado
   anterior sem migração inversa (migrações só avançam — o mesmo princípio
   que a flag `CHAT_ROUTER_ENABLED` usa: rollback sem tocar em dados).
6. Nunca commitar `.env` (keys via `./scripts/restore-env.sh` a partir do Keychain,
   service `nutrimadhvi`).

---

## P0 — impacto direto e medível

### T1. Streaming do chat: parar de re-parsear Markdown a cada chunk
- **Problema**: `ChatViewModel._streamingText` (ChatViewModel.kt:2395) recebe o
  texto CUMULATIVO a cada chunk SSE; o bubble renderiza `RichText(Markdown(texto_total))`
  a cada atualização → O(n²) por resposta. Além disso, em `ChatScreen.kt` (~linha
  9744-9753) o corpo RichText está inlined DUAS vezes (modo seleção vs normal).
- **Fazer**:
  1. Durante o streaming, renderizar o bubble em texto plain (`Text` simples).
  2. Throttle a atualização do `_streamingText` para ~100-150 ms (juntar chunks).
  3. No evento `Done`, fazer o parse RichText/Markdown único e final.
  4. Extrair o corpo do bubble num `@Composable` único (eliminar o inline duplo).
- **Validar**: enviar mensagem longa, medir frame drops (Layout Inspector /
  `androidx.compose.ui.metrics`); comparar tempo de resposta percebido.
- **Risco**: baixo. Sem mudança de dados → sem backup de BD, mas fazer commit
  checkpoint antes (arquivo de 12k linhas).

### T2. Imagens do chat: thumbnails assíncronas + cache
- **Problema**: `ChatScreen.kt` (~linha 9662-9688) faz
  `ChatImageStore.decodeBitmapWithOrientation` DENTRO da composição, na main
  thread, SEM subsampling — cada foto original (3-15 MB, lossless por
  decisão de OCR) é decodificada a resolução completa para uma thumbnail.
  `inSampleSize` só existe nos paths de food images.
- **Fazer**:
  1. Criar cache de thumbnails: `LruCache<String, Bitmap>` (~20-40 entries)
     alimentado com `BitmapFactory.Options.inSampleSize` calculado para a
     largura do bubble.
  2. Fazer o decode fora da composição (coroutine, `remember + LaunchedEffect`
     ou pequeno loader) — nunca na composição.
  3. Manter o ficheiro original INTACTO no disco (necessário para OCR/visão);
     só a cópia em memória é subsampleada.
  4. Fazer o mesmo no segundo path (~linha 10868) e em `FoodImageStore`
     se ainda não houver.
- **Validar**: chat com 50+ mensagens com fotos → scroll fluido; heap não
  cresce sem fim (cache tem teto).
- **Risco**: baixo. Sem mudança de dados.

### T3. Índices em falta — migration v38
- **Problema**: schema v37 tem 12 índices, mas `food_entries` e `activities`
  NÃO têm nenhum. `FoodDao.getAll()` (ORDER BY timestamp DESC),
  `getFoodsBetween()`, `COUNT(*) WHERE timestamp >=` e
  `ActivityDao` (`WHERE shRecordId = ?`) fazem full scan — degrada linearmente
  com o histórico.
- **Fazer**:
  1. `Migration(37, 38)`:
     ```sql
     CREATE INDEX IF NOT EXISTS index_food_entries_timestamp ON food_entries(timestamp);
     CREATE INDEX IF NOT EXISTS index_activities_timestamp ON activities(timestamp);
     CREATE INDEX IF NOT EXISTS index_activities_shRecordId ON activities(shRecordId);
     ```
     (criados no `@Entity` ou na migration — seguir o padrão das migrations
     existentes em `ChatDatabase.kt`).
  2. Bump `version = 38` no `@Database`.
  3. Exportar schema (38.json) — o ksp arg `room.schemaLocation` já aponta
     para `app/schemas/`.
  4. **Backup da BD antes** (regra 2) e adicionar `testMigration37To38PreservesData`
     ao `DatabaseMigrationTest` (criar BD v37 com rows → migrar → as rows
     permanecem + `EXPLAIN QUERY PLAN` mostra os índices a serem usados).
- **Validar**: `./gradlew testDebugUnitTest` (inclui o teste de migração);
  instalar num dispositivo com dados reais e confirmar que os dados
  históricos aparecem intatos no Histórico.
- **Risco**: baixo (índices são aditivos, nunca perdem dados), mas é a ÚNICA
  tarefa das P0 que toca na BD → backup obrigatório.

---

## P1 — alto valor, esforço médio

### T4. History: paginação em vez de `SELECT *` completo
- `FoodDao.getAll()` devolve TODAS as refeições no `StateFlow`; a cada mutação
  da BD reemite a lista inteira. Com 1-2 anos de logging, caro.
- **Fazer**: query com `LIMIT/OFFSET` (ou keyset por timestamp) + "carregar
  mais" no fim da lista; `HistoryTab` passa a carregar páginas de 50-100.
- **Validar**: Histórico abre com só a 1.ª página; scroll até ao fim carrega
  o resto; filtros continuam a funcionar.

### T5. `activeMessages`: evitar reconstrução da timeline inteira a cada change
- A cada nova mensagem, o Room reemite a lista completa do session e
  `chatTimelineRows()` (ChatTimeline.kt) reconstrói todas as rows.
- **Fazer**: memoizar rows por referência/identidade da lista (ex.: só
  reconstruir o sufixo novo) OU dividir a timeline em "histórico estável"
  (sem recomposition) + "janela ativa" (últimos N rows que mudam).
- **Validar**: com session de meses, enviar mensagem → recompositions
  limitadas aos novos rows (Layout Inspector).

### T6. Estrutura: separar os tabs do `ChatScreen.kt` (12 241 linhas)
- `ChatViewModel` (4 638 linhas, 164 edges no graphify) é partilhado por todas
  as tabs do `HorizontalPager` — qualquer `StateFlow` pode invalidar recomposition
  em TODAS as pages (que vivem compostas em simultâneo).
- **Fazer** (incremental, 1 PR por tab): extrair cada tab num ficheiro próprio
  com os seus `collectAsStateWithLifecycle` locais; a médio prazo, ViewModels
  por tab. NÃO fazer tudo de uma vez.
- **Validar**: por tab, medir recompositions com o tab inativo a zero.
- **Nota**: trabalho de várias sessões; agendar depois de T1-T5.

---

## P2 — ganhos claros, esforço baixo

### T7. Vision: downsample antes do upload
- O ensemble (Gemini/Bitdeer) recebe o JPEG original de 3-15 MB.
- **Fazer**: downsample para ~1600 px / JPEG quality 85 só para o payload do
  upload; o original continua em disco para re-análise.
- **Cuidado**: `MacroPlausibilityGate`, `MealResultJudge` e
  `CorrectionCalibrator` dependem da qualidade da análise — fazer 1 teste A/B
  (6-10 fotos reais) antes de ativar por defeito; deixar mode flag
  (`VISION_UPLOAD_FULL_RES`) para rollback instantâneo.

### T8. Chat: linhas cruas de 31 dias só para `DataQuery`
- O bloco `[PRE-FETCHED]` com as linhas cruas do snapshot canónico (31 dias)
  é injetado EM TODOS os turnos, incluindo Reasoning curto → tokens + latência.
- **Fazer**: para intents `Reasoning`, injetar apenas os agregados
  ("Today consumed — 1450 kcal") e não as linhas cruas.
- **Validar**: 10 perguntas de reasoning → comparar tokens/latência; confirmar
  que as respostas não pioraram (a justificação original em
  `ChatOrchestrator.kt:546-550` é que as linhas impedem a modelo de
  improvisar — testar que os números continuam a bater certo).

### T9. Debug sem minify (opcional, trade-off consciente)
- `app/build.gradle.kts:99-109` tem minify+R8 em DEBUG de propósito
  (manter ProGuard honesto). Se a iteração local estiver lenta, adicionar um
  3.º buildType `dev` sem minify; CI mantém o debug minified.
- **Só fazer se** o build local for uma queixa real.

### T10. Snapshot tables sem teto (guardar para o futuro)
- `target_snapshots`, `bmr_snapshots`, `body_composition_snapshots`,
  `daily_activity_snapshots` crescem 1 row/dia e os resolvers fazem
  `SELECT * ORDER BY`. A 2-3 anos, milhares de rows.
- **Fazer** (quando chegar a ~1000 rows): leitura por janela no resolver +
  política de retenção (ex.: manter diário nos primeiros 90 dias, depois
  semanal). Não fazer agora — o problema ainda não é mensurável.

---

## O que está bem (NÃO tocar)
- `DataFetcher` — fetch paralelo + reuso do cache do snapshot para janelas ≤31d.
- `PredictedQa` (Frente I) — respostas instantâneas a perguntas repetidas.
- Reconciliação de sends órfãos no background (`ChatOrchestrator`).
- `ChatImageStore` — imagens no disco, não base64 na BD.
- `InsaLocalSource` — SQLite read-only em cache.

---

## Ordem de execução
`T3 (índices, rápido, valida a pipeline de migração) → T1 → T2 → T4 → T5 → T8 → T7 → T6 (último, várias sessões) → T9/T10 (quando necessário)`

Cada tarefa termina com: testes verdes + `assembleDebug` + versão bumpada + commit
com mensagem `perf: <tema>` (seguir o estilo do log git).
