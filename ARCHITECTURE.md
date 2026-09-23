# ARCHITECTURE

## Principio fundamental

Los mundos **NO serán reciclados dentro de una JVM Minecraft en ejecución**.

No se implementará:

- `dynamic ServerLevel` hot-swap
- custom dimension recycling
- unload/delete/re-register de `ServerLevel`
- `SeededServerLevel`

En su lugar, la arquitectura usa **dos servidores Fabric completamente separados** (blue/green), cada uno
con su propia JVM, y un proxy que redirige jugadores entre ellos sin que el cliente se desconecte.

## Entrada (público)

```text
Player
  → playit.gg   (FUTURE, no configurado todavía — FASE 5)
  → Velocity    (proxy local, certificado en FASE 3: localhost:25565)
```

## Runtime

```text
Velocity (Java 25)
  → Server A (Java 17) : 25566
  → Server B (Java 17) : 25567
```

Tres procesos JVM independientes (`Velocity JVM != Server A JVM != Server B JVM`), verificados corriendo
simultáneamente con PIDs distintos, cada uno en su ejecutable Java canónico (`scripts/windows/java-paths.ps1`).
Desde FASE 4, Velocity aloja un plugin propio (`hardcore-coordinator`, `velocity-plugin/hardcore-coordinator/`)
que mantiene el modelo ACTIVE/STANDBY y expone `/hs status` / `/hs switch` — el switching ya no depende de
`/server` (que sigue disponible solo como herramienta diagnóstica). El plugin no persiste estado entre
reinicios de Velocity todavía (llega en FASE 5 junto con el recycle automático).

## Backends

```text
Server A   — Fabric standalone, JVM propia, world propio, puerto propio
Server B   — Fabric standalone, JVM propia, world propio, puerto propio
```

Ambos comparten:

- misma versión de Minecraft (1.20.1)
- misma versión de Fabric Loader (0.19.5) y Fabric API
- mismos mods obligatorios (`shared/server-mods`)
- misma configuración común (`shared/configs`)

Cada uno tiene independientemente:

- su propia JVM
- su propio `server.properties`
- su propio `world/`
- sus propios logs
- su propio puerto

## Puertos (todos locales todavía, ninguno expuesto externamente hasta FASE 5)

```text
PUBLIC_ENTRYPOINT = Velocity : 25565   (RCON: n/a — ver Seguridad de procesos)
BACKEND_A         = localhost : 25566  (RCON : 25576)
BACKEND_B         = localhost : 25567  (RCON : 25577)
```

Solo Velocity debe ser público. A y B son backends internos y nunca deben exponerse directamente a Internet.
playit.gg (cuando se configure, FASE 5) apuntará únicamente a Velocity, nunca a A o B.

**Hallazgo de FASE 3**: con la configuración actual (`online-mode=true` + FabricProxy-Lite
`hackOnlineMode=true`), un cliente conectado directamente a `localhost:25566`/`:25567` es **aceptado**, no
rechazado — FabricProxy-Lite gestiona el forwarding de identidad para conexiones vía proxy, pero no añade un
firewall que bloquee conexiones directas por sí mismo. Antes de FASE 5 (exposición pública vía playit.gg),
aplicar `server-ip=127.0.0.1` en `server.properties` de A y B para que un cliente externo no pueda alcanzar
los backends sin pasar por Velocity (esto no impide una conexión directa *desde la misma máquina*, que
seguirá funcionando vía loopback — la protección real es contra acceso externo). Ver `VERSION_LOCK.md`,
sección "Hallazgos de FASE 3".

## Lifecycle (implementado desde FASE 5 — trigger sigue siendo `/hs switch`, no muerte todavía)

```text
A ACTIVE
B READY

/hs switch
    ↓
players A → B   (vía Velocity, sin desconexión)
    ↓
B ACTIVE, A DRAINING
    ↓ (en background - el jugador ya está jugando en B)
A:
  ZERO PLAYERS confirmado (RCON directo al backend, no solo la vista de Velocity)
  STOP (RCON stop + verificación de PID/puertos)
  DELETE WORLD (path validado contra whitelist fija, nunca un path externo)
  NEW SEED (64 bits, distinta de la anterior)
  START
  seed real verificada == seed solicitada
    ↓
A READY (generation+1)
```

La siguiente `/hs switch` invierte los roles (B se recicla, A queda ACTIVE), y así sucesivamente. FASE 7+
sustituirá el trigger manual por muerte de jugador, reutilizando exactamente este mismo mecanismo.

## Estados del sistema (implementados desde FASE 4-5)

```text
ACTIVE
READY
DRAINING
RECYCLING
STARTING (implícito dentro de RECYCLING desde la perspectiva de state.json - ver nota abajo)
FAILED
```

Ejemplo real (ver `state.json`, gestionado por `StateManager` dentro del plugin `hardcore-coordinator`,
no por un `controller/` externo — ese directorio sigue sin usarse, ver `controller/README.md`):

```json
{
  "runId": 8,
  "active": "server-b",
  "servers": {
    "server-b": { "status": "ACTIVE", "generation": 3, "seed": 8710430450032173306 },
    "server-a": { "status": "READY", "generation": 4, "seed": 1483838162082278231 }
  }
}
```

Nota: `recycle-backend.ps1` corre como un único proceso externo bloqueante desde la perspectiva del plugin
(STOP→DELETE→SEED→START→health-check todo en una sola invocación), así que `state.json` solo distingue
`DRAINING`→`RECYCLING`→(`READY`|`FAILED`) — el sub-paso `STARTING` es visible en los logs de progreso del
script (`logs/recycle/<operationId>.log`) pero no como una transición de estado persistida aparte.

## Seguridad de procesos

**Regla permanente**: nunca usar `taskkill /IM java.exe` ni matar todos los procesos Java indiscriminadamente.

Cada instancia (Server A, Server B, Velocity) debe identificarse siempre por la combinación de:

- PID
- working directory
- puerto

Cualquier script futuro que detenga/reinicie un servidor debe verificar estos tres datos antes de actuar
sobre un proceso.

## Modpack de cliente vs mods de servidor

`client/fabulously-optimized/` (modpack de CLIENTE, Fabulously Optimized) es conceptualmente independiente
de `shared/server-mods/` (mods obligatorios de SERVIDOR, sincronizados a `server-a/mods` y `server-b/mods`).
Fabulously Optimized **no genera los mundos del servidor** y no debe mezclarse con el modset del servidor.

## Estado por fase

- FASE 0-2 (PASS): Server A y Server B standalone, certificados, sin proxy.
- FASE 3 (PASS): Velocity delante de A/B, forwarding moderno, switching manual (`/server`) certificado con
  6 transferencias estables, identidad de jugador (UUID) verificada idéntica en A y B.
- FASE 4 (PASS): plugin `hardcore-coordinator` propio, switching programático (`/hs switch`) certificado con
  6 ciclos, protección contra doble-switch, rechazo cuando el standby no responde.
- FASE 5 (PASS): recycle automático del backend anterior tras cada switch (STOP → DELETE WORLD → NEW SEED →
  START → READY), en background (el jugador no espera), estados
  ACTIVE/READY/DRAINING/RECYCLING/FAILED, `generation`/`runId`, persistencia atómica de estado
  (`state.json`, sobrevive reinicios de Velocity).
- FASE 6 (PASS): endurance — 13 ciclos switch+recycle consecutivos sin degradación (0 fallos, seeds únicas,
  PIDs rotando limpiamente, Velocity nunca reiniciado), más recovery de `state.json` tras un reinicio
  controlado de Velocity. Sin cambios funcionales (ningún bug real encontrado durante la prueba).
- FASE 7 (PASS): trigger de muerte global — la muerte de cualquier jugador invoca el mismo pipeline
  switch+recycle ya certificado (ningún mecanismo de transferencia nuevo). Certificado en vivo con
  7 muertes reales, incluyendo el caso límite muerte-durante-recycle-del-standby. Ver sección
  "Trigger de muerte global (FASE 7)" más abajo para el detalle del mecanismo.
- FASE 8 (PARTIAL): contención local de run perdida (`SPECTATOR` forzado a todos los jugadores del backend
  perdedor, no solo a quien murió) + UX mínima (título/chat) + fix real de un bug de carrera al transferir
  2+ jugadores en paralelo al mismo backend (ver sección "Contención local y UX (FASE 8)" más abajo).
  Certificado en vivo con 12 transiciones muerte→switch→recycle reales, modset nuevo del usuario auditado
  (`MODSET_PARITY = PASS`, 17 mods), modo offline + whitelist + dificultad `hard` habilitados a pedido del
  usuario. **No se ejecutó** el checklist formal de verificación del propio spec de FASE 8 (test de estado
  marcado antes/después de morir, test de fuga de playerdata entre generaciones, test limpio de
  "Respawn durante WAITING_FOR_STANDBY", smoke test de features de los mods nuevos en el End) — por eso
  queda como PARTIAL, no PASS, hasta que se ejecute esa evidencia.
- playit.gg y multijugador (FASE 10/13 en el roadmap original): confirmados **WORKING** por el usuario,
  con muertes reales certificadas en Overworld/Nether/End — se tomó como la realidad actual del proyecto
  sin re-investigar, aunque quedan documentadas fuera de su orden original en el roadmap.
- FASE 9 (READY_FOR_USER_VALIDATION): tres mejoras visuales, mod nuevo `HardcoreHud` (server-side, sin
  mod de cliente), ninguna toca el pipeline de muerte/switch/recycle. TAB hearts vía scoreboard vanilla
  (`health` + `hearts` + slot `list`), re-creado en cada arranque de JVM porque `world/` se borra en cada
  recycle. (La brújula hacia el compañero se retiró en 2026-09-22 - el usuario ya tiene otro mod que cubre
  esa función.) Estrategia de corazones Hardcore decidida con evidencia: `hardcore=false` + resource pack
  visual (`HardcoreVisuals-1.20.1.zip`, checksums verificados) en vez de `hardcore=true`, porque en un
  servidor dedicado `hardcore=true` solo afecta individualmente a quien muere (no termina la run para
  todos, no borra el mundo) — un modelo que se solapa y compite con la contención de FASE 8 sin aportar
  nada que el proyecto no tenga ya. Ver sección "TAB hearts y contador de muertes (FASE 9)" más abajo y
  README.md para la matriz de comparación completa. **No se declara PASS** hasta validación manual del
  usuario en el juego.
- Todavía fuera de alcance: el checklist formal de FASE 8 listado arriba, validación manual de FASE 9.

## Trigger de muerte global (FASE 7)

```text
Jugador muere en Server X (ACTIVE)
    ↓
HardcoreDeathSignal (mod Fabric, server-side, sensor puro)
  ServerLivingEntityEvents.AFTER_DEATH (no cancelable, dispara tras la muerte real)
  → filtra a ServerPlayer real
  → escribe <spoolDir>/inbox/<eventId>.json  (atómico: .tmp + Files.move ATOMIC_MOVE)
    ↓
HardcoreCoordinator (proceso Velocity) — DeathCoordinator.pollOnce() cada 100ms
  valida: eventId duplicado? backend desconocido? backend != active actual?
          timestamp anterior al inicio del run activo (evento obsoleto)?
          phase != ACTIVE (ya hay una muerte en curso)?
    ↓ (evento válido)
  state.json: phase=ENDING, pendingDeath={eventId, playerUuid, ...}   [persistido ANTES de mover el archivo]
  mueve el evento a processed/
    ↓
attemptDeathSwitch()
    ├─ standby READY  → phase=SWITCHING → SwitchService.switchNow()  (el mismo de FASE 4, sin duplicar)
    │                                     → commitSwitch(): runId+1, phase=ACTIVE, pendingDeath=null
    │                                     → RecycleService recicla el backend perdedor (FASE 5, sin duplicar)
    │
    └─ standby RECYCLING/no-READY  → phase=WAITING_FOR_STANDBY  (la muerte NO se pierde)
                                        ↓
                                    SwitchService notifica onBackendRecycled() cuando el standby
                                    termina de reciclar
                                        ↓
                                    si phase seguía WAITING_FOR_STANDBY para ese backend
                                        → reintenta attemptDeathSwitch() automáticamente
```

Puntos de diseño clave:

- El mod (`HardcoreDeathSignal`) y el plugin (`HardcoreCoordinator`) están desacoplados por el spool de
  archivos — el mod nunca sabe si hubo switch, el plugin nunca lee memoria del proceso del backend.
- `RunPhase` (ACTIVE/ENDING/WAITING_FOR_STANDBY/SWITCHING) es ortogonal al estado por-backend
  (ACTIVE/READY/DRAINING/RECYCLING/STARTING/FAILED) — una muerte puede quedar "pendiente" mientras los
  backends individuales siguen su propio ciclo de vida normal.
- `pendingDeath` se persiste en `state.json` en el mismo momento en que se acepta el evento, no al
  completar el switch — así un reinicio de Velocity a mitad de `WAITING_FOR_STANDBY` recupera la muerte
  pendiente al arrancar y reintenta el switch automáticamente, sin perder la run perdida.
- `PlayerChooseInitialServerEvent` se añadió en esta fase porque es la primera en la que un jugador puede
  reconectarse legítimamente después de un switch disparado sin su intervención (`velocity.toml` por sí
  solo no conoce el ACTIVE dinámico).

## Contención local y UX (FASE 8)

```text
Muerte en Server X (ACTIVE)
    ↓
HardcoreDeathSignal, EN LA MISMA JVM, de forma síncrona:
  localRunEnded = true   (volatile, solo en memoria de ESTA JVM - nunca en disco)
  TODOS los jugadores conectados a Server X → GameType.SPECTATOR (no solo quien murió)
  (además: AFTER_RESPAWN re-bloquea a SPECTATOR; JOIN contiene a quien se una tarde)
    ↓ (evento de muerte escrito al spool, como siempre)
HardcoreCoordinator recibe el evento → RUN_LOST_UX_SENT (título/chat, una sola vez)
    ↓
SwitchService.switchNow() transfiere los jugadores de Server X al standby
  — SECUENCIALMENTE, uno a la vez (FASE 8: nunca en paralelo, ver más abajo)
    ↓
commitSwitch() → NEW_RUN_UX_SENT → RecycleService recicla Server X
    ↓
Server X: STOP → DELETE WORLD → nueva JVM
    ↓
localRunEnded vuelve a false (JVM nueva, nunca se persistió)
```

**Bug real encontrado y corregido en vivo (con 2 jugadores conectados a la vez):** la versión original de
`SwitchService.doTransfer()` disparaba `Player.createConnectionRequest(target).connect()` para todos los
jugadores del backend perdedor **en paralelo** (`Stream.map(...)` + `CompletableFuture.allOf(...)`). Con 2
jugadores conectándose al mismo backend recién arrancado en el mismo instante, el propio motor de
Minecraft/Fabric lanzó una `ConcurrentModificationException` real (`HashMap.computeIfAbsent` sobre una
caché de codificación de registro que no es thread-safe bajo logins simultáneos) — confirmado leyendo el
log del backend afectado, no adivinado. Velocity reportó esto como `SERVER_DISCONNECTED` para la segunda
transferencia, `finishSwitch()` vio un `SWITCH_PARTIAL_FAILURE`, nunca llegó a `commitSwitch()` (ni por
tanto a `beginRecycle()`), y el jugador fallido quedó atrapado en `SPECTATOR` (por la contención de arriba)
en un backend que el coordinador seguía creyendo activo pero para el que ya no había ningún disparador de
reintento pendiente — `onBackendRecycled()` solo se dispara cuando termina un recycle, y aquí nunca se
había iniciado ninguno.

**Fix:** dos cambios, ambos reutilizando el pipeline ya certificado, sin mecanismo nuevo:

1. `doTransfer()` ahora encadena las transferencias secuencialmente (una `CompletableFuture` a la vez) —
   elimina la carrera del motor para cualquier N de jugadores, no solo 2.
2. `DeathCoordinator.pollOnce()` ahora reintenta `attemptDeathSwitch()` en cada poll (100ms) mientras la
   fase sea `WAITING_FOR_STANDBY`, en vez de depender exclusivamente de `onBackendRecycled()` — así
   cualquier fallo de transferencia futuro, sea cual sea la causa, no puede dejar el run atascado para
   siempre; el propio `switchNow()` ya es idempotente/seguro ante reintentos gracias al guard
   `switchInProgress`.

**Nota de UX (spec sección 23):** `RunUx` envía título/chat con try/catch alrededor de cada llamada — un
fallo de UX (por ejemplo, un cliente que se desconectó justo antes) solo genera un warning en el log y
nunca bloquea ni revierte el switch/recycle real.

## TAB hearts y contador de muertes (FASE 9)

```text
HardcoreHud (mod Fabric propio, server-side, sin dependencia del pipeline de muerte)

ServerLifecycleEvents.SERVER_STARTED (una vez por JVM, incl. tras cada recycle)
    → scoreboard.addObjective("hc_health", HEALTH, "Health", HEARTS)   [vanilla, idempotente]
    → scoreboard.setDisplayObjective(DISPLAY_SLOT_LIST, objective)
    → TAB list ahora muestra corazones de todos los jugadores conectados
    → scoreboard.addObjective("hc_deaths", DUMMY, "Runs Lost", INTEGER)   [idempotente]
    → scoreboard.setDisplayObjective(DISPLAY_SLOT_SIDEBAR, objective)

ServerTickEvents.END_SERVER_TICK
    cada 20 ticks (~1x/seg) — contador de muertes:
        leer runId de state.json de HardcoreCoordinator (proceso Velocity distinto, solo lectura)
        → objective.setDisplayName("☠ Runs Lost: " + runId)
        → sincronizar filas: score=0 por cada jugador conectado ahora, quitar los que ya no estén
```

Historial de la brújula hacia el compañero (retirada en 2026-09-22): pasó por tres iteraciones de feedback
directo del usuario (v0.1.0 flecha simple sin color; v0.2.0 punto gráfico sobre la barra de XP con mod de
cliente separado, retirada tras un error de instalación; v0.3.0 flechas de texto coloreadas 100% servidor)
hasta que el usuario indicó que ya tenía otro mod que cubre esa función, así que se eliminó por completo de
`HardcoreHudMod` (no queda mod de cliente ni lógica de brújula en el proyecto).

El bootstrap del scoreboard no lee `state.json` — opera exclusivamente sobre
`server.getPlayerList().getPlayers()` de la JVM en la que corre; el contador de muertes es la única
excepción, y en modo exclusivamente lectura (spec sección 4: no tocar el ciclo de vida real de `runId`).
Esto es intencional (spec sección 6.3: no acoplar lógica visual al sensor de muerte) y es también lo que
hace que el HUD se recupere solo tras un switch/recycle/reconexión: la próxima ejecución del tick
simplemente ve una lista de jugadores distinta y recalcula, sin ninguna integración explícita con
`SwitchService`.
