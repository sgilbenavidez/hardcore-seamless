# Minecraft Hardcore Seamless

Servidor Minecraft Hardcore cooperativo donde todos los jugadores pertenecen a una misma run. Cuando muere
cualquier jugador, la run completa se pierde y todos los jugadores cambian automáticamente a otra instancia
ya preparada (mundo nuevo, seed nueva, progreso fresco) **sin desconectarse manualmente, sin volver al menú
multijugador, sin cambiar de IP y sin reiniciar el cliente**.

Este es un proyecto reconstruido desde cero. El proyecto anterior (basado en `ServerLevel` dinámicos dentro
de una misma JVM de Forge) fue eliminado y no se recupera ni se copia. Ver `ARCHITECTURE.md` para el motivo
del cambio de arquitectura.

```text
CURRENT_PHASE = 9 (READY_FOR_USER_VALIDATION)
HARDCORE_AUTOMATION = IMPLEMENTED (death -> automatic run loss + switch + recycle + local run-loss
                      containment + minimal UX; hardcore=false + Hardcore Visuals resource pack)
```

## Versiones (ver `VERSION_LOCK.md` para el detalle verificado)

| Componente | Versión |
|---|---|
| Minecraft | 1.20.1 |
| Java — backends Fabric (A/B) | 17 (instalado localmente en `tools/java17/`) |
| Java — proxy Velocity | 25 (instalado localmente en `tools/java25/`) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.92.12+1.20.1 |
| Velocity | 4.2.0 (build 30) |
| Fabric proxy forwarding | FabricProxy-Lite 2.6.0 |
| Fabulously Optimized (cliente) | 5.4.1 |

## Mods utilizados

Los `.jar` **no** se incluyen en el repositorio: descárgalos de Modrinth o CurseForge (la versión exacta
está en el nombre del archivo) y cópialos en `server-a/mods/` **y** `server-b/mods/` (ambos backends deben
tener exactamente el mismo modset — `scripts/windows/compare-server-mods.ps1` lo verifica).

| Categoría | Mod | Archivo (versión) |
|---|---|---|
| Base | Fabric API | `fabric-api-0.92.12+1.20.1.jar` |
| Proxy | FabricProxy-Lite | `FabricProxy-Lite-2.6.0.jar` |
| Rendimiento | Lithium | `lithium-fabric-mc1.20.1-0.11.2.jar` |
| Rendimiento | C2ME | `c2me-fabric-mc1.20.1-0.2.0+alpha.11.18.jar` |
| Rendimiento | FerriteCore | `ferritecore-6.0.0-fabric.jar` |
| Rendimiento | Krypton | `krypton-0.2.3.jar` |
| Rendimiento | ModernFix | `modernfix-fabric-5.25.2+mc1.20.1.jar` |
| Rendimiento | Alternate Current | `alternate-current-mc1.20-1.9.0.jar` |
| Diagnóstico | spark | `spark-1.10.53-fabric.jar` |
| Dificultad / mobs | Eldritch Mobs | `eldritch-mobs-1.15.2.jar` |
| Dificultad / mobs | Improved Mobs | `improvedmobs-1.20.1-1.13.7-fabric.jar` |
| Dificultad / mobs | Mutant Monsters | `MutantMonsters-v8.0.8-1.20.1-Fabric.jar` |
| Dificultad / mobs | Enhanced Celestials 2 (Core, Default Lunar Events, Shaders) | `Enhanced-Celestials-2-*-Fabric-1.20.1-*.jar` |
| Final del juego | Tru.e Ending | `tru.e-ending-v1.1.0c.jar` |
| Estructuras | YUNG's Better Dungeons / Mineshafts / Nether Fortresses / Strongholds / End Island | `YungsBetter*-1.20-Fabric-*.jar` |
| Progresión | Pufferfish's Skills + Default Skill Trees + Pufferfish's Attributes | `puffish_skills-0.19.1`, `default_skill_trees-1.1`, `puffish_attributes-0.8.2` |
| Progresión | Weapon Leveling | `weaponleveling-1.20.1-2.2.2-fabric.jar` |
| Progresión | Tool Level Up (*ver nota*) | `tool-level-up-1.0.2.jar` |
| Utilidad | Player Locator Plus | `player-locator-plus-2.2.0.jar` |
| Utilidad | TrashSlot | `trashslot-fabric-1.20.1-15.1.5.jar` |
| Utilidad | AttributeFix | `AttributeFix-Fabric-1.20.1-21.0.5.jar` |
| Librerías | Architectury, Balm, Cloth Config, CorgiLib, Data Anchor, Forge Config API Port, Puzzles Lib, TenshiLib, YUNG's API | (dependencias de los mods anteriores) |
| **Propios** | HardcoreDeathSignal (`fabric-mods/hardcore-death-signal`) | se compila desde este repo |
| **Propios** | HardcoreHud (`fabric-mods/hardcore-hud`) | se compila desde este repo (también va en el cliente) |
| **Propios** | HardcoreCoordinator — plugin de Velocity (`velocity-plugin/hardcore-coordinator`) | se compila desde este repo |

> **Nota sobre Tool Level Up:** en nuestro servidor usamos una versión parcheada para arreglar un bug en
> multijugador. El mod original es "All Rights Reserved", así que el parche **no** se publica. Usa la
> versión original o quítalo del modset.

## Arquitectura (ver `ARCHITECTURE.md` para el detalle)

```text
Player → playit.gg (futuro, FASE 5) → Velocity :25565 (Java 25) → Server A :25566 / Server B :25567 (Java 17)
```

Dos servidores Fabric independientes (A/B, blue-green) detrás de un proxy Velocity certificado en FASE 3 —
switching manual entre A y B vía `/server server-a` / `/server server-b`, sin desconexión del cliente, con
identidad de jugador (UUID) idéntica en ambos backends. Cuando una run termine, los jugadores se moverán al
backend en espera (STANDBY → ACTIVE) mientras el backend anterior se recicla (STOP → DELETE WORLD → NEW SEED
→ START → READY → STANDBY) — ese switch **automático** todavía no existe (FASE 4 en adelante). Los mundos
**no** se reciclan dentro de una JVM en ejecución.

## Cómo arrancar el sistema completo (arranque normal)

Los tres procesos (Server A, Server B, Velocity) corren cada uno **en primer plano** (foreground) — no hay
un solo comando que levante todo; se necesitan **3 ventanas de PowerShell separadas**, una por proceso, y
deben quedarse abiertas mientras el servidor esté en uso. Orden obligatorio: los dos backends primero,
Velocity al final (Velocity necesita que A y B ya estén escuchando para registrarlos como backends).

```powershell
# Ventana 1
powershell -File scripts\windows\start-server-a.ps1

# Ventana 2
powershell -File scripts\windows\start-server-b.ps1

# Ventana 3 (después de que A y B terminen de cargar el mundo)
powershell -File scripts\windows\start-velocity.ps1
```

Luego, en Minecraft 1.20.1: **Direct Connect** → `localhost:25565` (nunca `25566`/`25567` directamente en
uso normal — ver "Hallazgo abierto" más abajo). Como el modo es offline (`online-mode=false`, FASE 8),
cualquier username en la whitelist de A/B puede entrar sin cuenta premium.

**Antes del primer arranque en una máquina nueva:** el jar de `hardcore-coordinator` no está en git
(`proxy/velocity/plugins/*.jar` es gitignored, build reproducible) — hay que construirlo y desplegarlo una
vez con Velocity **detenido**:

```powershell
powershell -File scripts\windows\build-hardcore-coordinator.ps1
powershell -File scripts\windows\deploy-hardcore-coordinator.ps1
```

**Para detener limpiamente**, en orden inverso (Velocity no tiene por qué pararse primero, pero evita que
alguien se quede a mitad de un switch):

```powershell
# Consola de Velocity (ventana 3): escribir "end" directamente, o si no es interactivo:
powershell -File scripts\windows\stop-velocity.ps1 -Force

powershell -File scripts\windows\stop-server-b.ps1
powershell -File scripts\windows\stop-server-a.ps1
```

**Diagnóstico rápido:** `scripts\windows\check-ports.ps1` (25565/25566/25567 libres u ocupados) y
`scripts\windows\show-processes.ps1` (PID + working directory + puerto de cada proceso Java vivo) — útiles
antes de arrancar o si algo no levanta.

## Estructura de carpetas

```text
E:\minecraft-hardcore\
│
├── README.md
├── VERSION_LOCK.md
├── ARCHITECTURE.md
├── .gitignore
│
├── proxy\velocity\        Velocity 4.2.0 — certificado en FASE 3, único entrypoint local (25565)
│
├── server-a\               backend Fabric A (standalone, JVM propia)
│   ├── mods\
│   ├── config\
│   ├── world\
│   └── runtime\
│
├── server-b\               backend Fabric B (standalone, JVM propia)
│   ├── mods\
│   ├── config\
│   ├── world\
│   └── runtime\
│
├── shared\                 assets comunes a sincronizar entre A y B
│   ├── server-mods\
│   ├── configs\
│   ├── resource-pack\
│   └── templates\
│
├── controller\              orquestador futuro de ACTIVE/STANDBY/switch/recycle (aún no implementado)
│
├── client\fabulously-optimized\   modpack de CLIENTE (separado de los mods de servidor)
│
├── scripts\
│   ├── windows\             utilidades de diagnóstico (PowerShell) + java-paths.ps1
│   └── tools\
│
├── tools\
│   ├── java17\              JDK Temurin 17 local (backends Fabric A/B; portable, excluido de git)
│   └── java25\              JDK Temurin 25 local (proxy Velocity; portable, excluido de git)
│
└── logs\
```

## Cómo se desarrollará (roadmap por fases)

Cada fase debe terminar en `PASS` antes de avanzar a la siguiente. No se acumulan varios sistemas nuevos en
una sola fase (p. ej. Velocity + playit + death mod + world recycle nunca se implementan juntos).

```text
FASE 0   Bootstrap, versiones y arquitectura                         ← PASS
FASE 1   Fabric Server A funcional                                   ← PASS
FASE 2   Fabric Server B funcional                                   ← PASS
FASE 3   Velocity + conexión a A/B + switching manual                ← PASS
FASE 4   Automatización/control de switching A ↔ B (sin muerte)      ← PASS
FASE 5   Automatic backend recycle (world nuevo/seed nueva)          ← PASS
FASE 6   Endurance certification (13 ciclos switch+recycle + state recovery)  ← PASS
FASE 7   Global death trigger (muerte → switch+recycle automático)   ← PASS
FASE 8   Run-state reset & hardcore death UX (inventory/XP/stats fresh)  ← PARTIAL
FASE 9   Multiplayer HUD (TAB hearts, death counter) + hardcore visual UX  ← READY_FOR_USER_VALIDATION
FASE 10  playit.gg → Velocity                                       ← WORKING (confirmado por el usuario,
                                                                        fuera de orden - ver nota abajo)
FASE 11  10 muertes consecutivas end-to-end (público)
FASE 12  Fabulously Optimized cliente
FASE 13  Multiplayer                                                 ← WORKING (confirmado por el usuario,
                                                                        probado en Overworld/Nether/End)
FASE 14  Mods adicionales
```

## Regla de seguridad de procesos

**Nunca** ejecutar `taskkill /IM java.exe` ni matar todos los procesos Java. Cada instancia (Velocity, Server
A, Server B) se identifica siempre por PID + working directory + puerto. Ver `ARCHITECTURE.md`.

## Estado actual (FASE 9 lista para validación manual del usuario)

**Nota sobre orden de fases:** el usuario confirmó que `playit.gg` (acceso público) y multijugador con
múltiples jugadores conectados ya están funcionando en producción, incluyendo muertes reales certificadas
en Overworld, Nether y End con el pipeline completo (switch A/B + recycle + nueva run) — esto se adelantó
al roadmap documentado (FASE 10/13) y se toma como la realidad actual del proyecto sin re-investigarlo.

- FASE 0: **PASS**. Java 17 y Java 25 instalados localmente (`tools/java17/`, `tools/java25/`, ambos
  Eclipse Temurin, checksum verificado). Java 8 y Java 21 preexistentes en la máquina no se han tocado.
- FASE 1: **PASS**. `SERVER_A_STATUS = CERTIFIED`.
- FASE 2: **PASS**. `SERVER_B_STATUS = CERTIFIED`. A y B verificados corriendo simultáneamente, aislados
  (PID/world/logs/RCON distintos), con `VERSION_PARITY = PASS`, `MODSET_PARITY = PASS`,
  `RUNTIME_ISOLATION = PASS`.
- FASE 3: **PASS**. Velocity 4.2.0 delante de A/B, forwarding moderno, switching manual `/server` certificado
  (6 transferencias, cliente nunca se desconecta), identidad de jugador (UUID) verificada idéntica en A y B
  vía RCON, chat funcionando después de cada switch, backend restart detrás del proxy sin afectar a Velocity
  ni al otro backend.
- FASE 4: **PASS**. Plugin Velocity propio `hardcore-coordinator` (`/hs status`, `/hs switch`) certificado:
  switching programático A↔B (6 ciclos), sin usar `/server`, protección contra doble-switch, rechazo cuando
  el standby está caído, recuperación una vez el standby vuelve. Ver detalle operativo más abajo.
- Ningún mod de gameplay instalado (`Fabric API` + `FabricProxy-Lite`, idénticos en ambos backends).
- Ninguna lógica hardcore implementada. `hardcore=false` en ambos.
- Ninguna arquitectura de `ServerLevel` dinámico presente.
- FASE 5: **PASS**. Recycle automático del backend anterior tras cada switch (STOP → DELETE WORLD →
  NEW SEED → START → READY), completamente en background — el jugador nunca espera. Estados
  ACTIVE/READY/DRAINING/RECYCLING/STARTING/FAILED + `generation`/`runId` persistidos en `state.json`
  (escritura atómica, recuperados al reiniciar Velocity). `scripts/windows/recycle-backend.ps1` valida
  estrictamente el path del world antes de borrar nada (whitelist fija A/B, nunca un path externo), confirma
  cero jugadores vía RCON directo al backend (no solo la vista de Velocity), y verifica que la seed
  realmente aplicada coincide con la solicitada antes de declarar READY. Certificado en vivo: recycle de A
  y de B, rechazo de switch mientras el standby sigue reciclando, y el ciclo completo repetido con el
  usuario. Ver detalle operativo más abajo.
- FASE 6: **PASS**. Endurance: **13 ciclos** switch+recycle consecutivos (mínimo exigido: 10), cero fallos,
  las 15 seeds usadas (7 de A + 8 de B, incluyendo las de arranque) únicas, rotación de PID limpia en los
  13 recycles, `runId` avanzó exactamente 10→23, `Velocity PID` nunca cambió durante toda la sesión,
  0 excepciones/crash-reports, 0 procesos huérfanos, `MODSET_PARITY` idéntico al inicio y al final. Además
  se certificó `VELOCITY_STATE_RECOVERY`: parada+reinicio controlado de Velocity (A/B sin tocar) restauró
  `runId`/`active`/`generation`/`seed` exactamente iguales a como estaban antes.
- FASE 7: **PASS**. La muerte de un jugador ahora dispara automáticamente el mismo pipeline switch+recycle
  ya certificado — sin `/hs switch`, sin `/server`, sin reconexión manual. Mod Fabric server-side propio
  `hardcore-death-signal` (sensor puro: detecta `ServerLivingEntityEvents.AFTER_DEATH`, filtra a jugadores
  reales, escribe un evento JSON atómico) instalado idéntico en A/B. `HardcoreCoordinator` extendido con
  `RunPhase` (ACTIVE/ENDING/WAITING_FOR_STANDBY/SWITCHING) y `DeathCoordinator`, que reutiliza `SwitchService`
  sin ningún mecanismo de transferencia nuevo. Certificado en vivo con **7 muertes reales** (1 recuperada tras
  reinicio de Velocity + 6 completamente en vivo), incluyendo el caso crítico: muerte mientras el standby
  seguía `RECYCLING` → `DEATH_WAITING_FOR_STANDBY` → resume automático ~3s después de que el standby quedó
  `READY`, sin perder la muerte y sin intervención manual. `runId` avanzó exactamente +1 por muerte aceptada,
  cero excepciones, cero procesos huérfanos. También se corrigió un gap real de enrutamiento: sin esto,
  un jugador que se reconecta tras un switch podía caer en el backend equivocado porque `velocity.toml` fija
  un `try` estático — ahora `PlayerChooseInitialServerEvent` enruta siempre al ACTIVE real. Ver detalle
  operativo más abajo.
- FASE 8: **PARTIAL**. El usuario añadió manualmente un modset nuevo (17 mods en total contando
  `hardcore-death-signal` y `FabricProxy-Lite`) a `server-a/mods` y `server-b/mods` — auditado y certificado
  `MODSET_PARITY = PASS` (hash SHA-256 idéntico en A y B). `nyfsspiders-3.0.1` quedó en cuarentena
  (`runtime/modset-quarantine/`, no borrado) por requerir `fabricloader 0.18.4` exacto, incompatible con
  0.19.5 — decisión del usuario, no un bug. `hardcore-death-signal` pasó a **0.2.0**: además de sensor de
  muerte, ahora implementa contención local de run perdida (`localRunEnded`, puramente en memoria de esa
  JVM) — al morir, todos los jugadores conectados a ese backend (no solo quien murió) pasan a `SPECTATOR`
  de inmediato, el respawn se re-bloquea a `SPECTATOR` si el run ya terminó, y un jugador que se una tarde a
  un backend ya perdido también queda contenido. `HardcoreCoordinator` pasó de **0.3.0 a 0.5.0**: 0.4.0
  añadió UX mínima (título/chat "RUN LOST", "Preparing next run...", "RUN #N - New world started", cada
  uno emitido una sola vez por transición, sin afectar el pipeline si el envío falla). **Bug real
  encontrado y corregido en vivo con 2 jugadores conectados simultáneamente**: transferir varios jugadores
  en paralelo al mismo backend recién arrancado dispara una `ConcurrentModificationException` real dentro
  del propio motor de Minecraft/Fabric (una caché de codificación de registro no es thread-safe bajo logins
  simultáneos), que Velocity reporta como fallo de transferencia — un jugador quedaba atrapado en
  `SPECTATOR` en el backend perdedor, y el propio mecanismo de reintento se quedaba esperando para siempre
  porque solo se disparaba al completarse un recycle nuevo. **0.5.0** corrige ambas cosas: la transferencia
  de jugadores ahora es secuencial (uno a la vez, nunca en paralelo — funciona para N jugadores, no solo 2),
  y el reintento de `WAITING_FOR_STANDBY` ahora se re-evalúa en cada poll (100ms) mientras el standby esté
  `READY`, sin depender exclusivamente del evento de recycle. Certificado con **12 transiciones reales**
  muerte→switch→recycle durante la sesión (`runId` 30→42), 16 tests unitarios en verde. También, a pedido
  directo del usuario (fuera del alcance original de FASE 8): modo offline habilitado
  (`online-mode=false` en Velocity + `force-key-authentication=false` + `enforce-secure-profile=false` en
  A/B, para permitir clientes no-premium), whitelist activa y forzada en A/B (`white-list=true`,
  `enforce-whitelist=true`, UUIDs offline calculados y verificados con dos implementaciones independientes),
  y dificultad `hard` fijada de forma persistente (sobrevive a cualquier recycle futuro). **Lo que falta
  para poder declarar `FASE_8_STATUS = PASS`**: el propio spec de FASE 8 exige medir antes de asumir
  (sección 5) — no se ejecutó el test formal de estado marcado (inventario/armadura/XP/vida/hambre/
  efectos/ender chest antes de morir, comparado contra el estado tras el switch), el test de fuga entre
  generaciones, el test explícito de "click Respawn durante WAITING_FOR_STANDBY" (hubo un
  `WAITING_FOR_STANDBY` real, pero fue por el bug de arriba, no la prueba limpia que pide el spec), el
  checklist sistemático de gameplay post-switch, ni el smoke test formal de las features de los mods nuevos
  en el End (`dragonfight`, `YungsBetterEndIsland`). Sin esa evidencia no se declara PASS, aunque toda la
  ingeniería (contención + UX + fix del bug) esté implementada, desplegada y ejercitada extensivamente en
  vivo. Ver detalle operativo más abajo.
- FASE 9: **READY_FOR_USER_VALIDATION** (no PASS hasta que el usuario confirme visualmente los trabajos
  en el juego — ver sección 9 de la especificación de FASE 9). Tres mejoras visuales, ninguna toca el
  pipeline de muerte/switch/recycle certificado:
  1. **TAB hearts**: mecanismo 100% vanilla (`scoreboard objectives add hc_health health` +
     `rendertype hearts` + `setdisplay list`), verificado en vivo por RCON antes de escribir ningún código
     — no hizo falta ningún mod para la funcionalidad en sí. Como `world/` (donde vive el scoreboard) se
     borra en cada recycle, se creó un mod server-side mínimo nuevo, `hardcore-hud` (`fabric-mods/
     hardcore-hud/`, 0.1.0), que solo re-crea el objetivo en `ServerLifecycleEvents.SERVER_STARTED` — cero
     lógica de muerte/switch, deliberadamente separado de `HardcoreDeathSignal` (spec sección 6.3).
  2. ~~Brújula hacia el compañero~~: **retirada el 2026-09-22** a pedido del usuario, que ya tiene otro mod
     que cubre esa función. Pasó por tres iteraciones (flecha simple → punto en la barra de XP con mod de
     cliente, retirado tras un error de instalación → flechas de colores 100% servidor) antes de
     eliminarse por completo de `HardcoreHudMod` — no queda mod de cliente ni lógica de brújula en el
     proyecto. Ver historial completo en `ARCHITECTURE.md`.
  3. **Contador de muertes/runs**: añadido a pedido del usuario ("un contador arriba a la izquierda,
     discreto, con los nombres de los jugadores actuales"). Vanilla no tiene forma de colocar un panel por
     comando específicamente arriba-a-la-izquierda; el mecanismo discreto más cercano sin mod de cliente es
     el **sidebar** del scoreboard, que Minecraft renderiza arriba-a-la-**derecha** — se avisa esta
     diferencia de posición explícitamente, no se asumió que "esquina" = la esquina pedida. Un objetivo
     nuevo (`hc_deaths`, dummy) muestra en el título "☠ Runs Lost: N" y una fila con score 0 por cada
     jugador conectado ahora mismo (sin jugadores desconectados colgando). El número `N` se lee de
     `state.json` de `HardcoreCoordinator` — un proceso/JVM distinto (Velocity) — reutilizando el mismo
     patrón de desacople por archivo que ya existía entre `HardcoreDeathSignal` y `HardcoreCoordinator`
     para el spool de muertes; es una lectura de solo-visualización, nunca se usa para tomar decisiones
     (no toca el ciclo de vida real de `runId`).
  4. **Corazones Hardcore**: se investigó `hardcore=true` real vs. resource pack visual (hipótesis del
     proyecto, sección 7.4 del spec) — confirmada: `hardcore=true` en un servidor dedicado (no
     singleplayer) solo pone en `SPECTATOR` al jugador que murió, deja el mundo corriendo y a los demás
     jugando normal, que es precisamente el modelo "muerte individual" que este proyecto reemplaza por
     "run compartida perdida" — activar `hardcore=true` no aportaría nada que no exista ya y sí
     introduciría una segunda semántica de muerte parcialmente solapada. Se mantiene `hardcore=false` +
     resource pack `HardcoreVisuals-1.20.1.zip` (descargado, SHA-1 y SHA-256 verificados, `pack.mcmeta`
     confirmado `pack_format=15` = exactamente el formato de 1.20-1.20.1), configurado idéntico en A y B
     (`resource-pack`/`resource-pack-sha1` en `server.properties`, misma URL/hash → un switch A↔B nunca
     re-pide descargar el pack). Ver matriz de comparación completa más abajo.
- playit.gg: **WORKING** (confirmado por el usuario, fuera del orden documentado del roadmap — no
  re-investigado, ver nota en el encabezado de esta sección).
- **Hallazgo abierto, no bloqueante**: conexión directa a 25566/25567 (sin pasar por Velocity) es aceptada,
  no rechazada. Ver `VERSION_LOCK.md` → "Hallazgos de FASE 3" para la mitigación recomendada, ahora más
  relevante al estar expuesto públicamente vía playit.gg.

### Server A — datos operativos

```text
SERVER_A_STATUS      = CERTIFIED
PORT                 = 25566
JAVA                 = 17 (E:\minecraft-hardcore\tools\java17\jdk-17.0.20.1+1\bin\java.exe)
MINECRAFT            = 1.20.1
FABRIC_LOADER        = 0.19.5
FABRIC_API           = 0.92.12+1.20.1
WORLD_SEED           = 8938474561507105424
RCON                 = enabled, 127.0.0.1:25576 (password in server-a/runtime/rcon-password.local.txt, gitignored)
```

**Arrancar:** `powershell -File scripts\windows\start-server-a.ps1` (usa siempre `$Java17Exe` de
`java-paths.ps1`, nunca `java` a secas; se niega a arrancar si el puerto 25566 ya está ocupado).

**Detener limpiamente:** `powershell -File scripts\windows\stop-server-a.ps1` (envía `stop` por RCON y espera
la salida real del proceso; nunca usa `taskkill /IM java.exe`; con `-Force` permite un último recurso que
solo actúa sobre el PID exacto verificado por command line, no un kill global).

**Conectar como cliente:** ya no se recomienda para uso normal — desde FASE 3 el entrypoint es Velocity
(`localhost:25565`). Conectar directamente a `localhost:25566` sigue siendo posible (ver "Hallazgo abierto"
arriba) y es útil solo para diagnóstico aislado de A.

### Server B — datos operativos

```text
SERVER_B_STATUS      = CERTIFIED
PORT                 = 25567
JAVA                 = 17 (E:\minecraft-hardcore\tools\java17\jdk-17.0.20.1+1\bin\java.exe)
MINECRAFT            = 1.20.1
FABRIC_LOADER        = 0.19.5
FABRIC_API           = 0.92.12+1.20.1  (identical sha256 to Server A's jar — MODSET_PARITY = PASS)
WORLD_SEED           = 6605584822522826534  (different from Server A's, as expected)
RCON                 = enabled, 127.0.0.1:25577 (password in server-b/runtime/rcon-password.local.txt, gitignored, distinct from A's)
```

**Arrancar:** `powershell -File scripts\windows\start-server-b.ps1` (mismas reglas que A: `$Java17Exe`
canónico, se niega a arrancar si 25567 ya está ocupado).

**Detener limpiamente:** `powershell -File scripts\windows\stop-server-b.ps1` (RCON `stop` + verificación de
salida real; `-Force` solo actúa sobre el PID exacto verificado).

**Comparar modsets A/B:** `powershell -File scripts\windows\compare-server-mods.ps1` → compara por filename +
SHA-256; debe dar `MODSET_PARITY = PASS` (2 jars: Fabric API + FabricProxy-Lite, idénticos en A y B).

**Conectar como cliente:** igual que A — usar Velocity (`localhost:25565`) en uso normal; conexión directa a
`localhost:25567` posible pero no recomendada.

`scripts\windows\rcon-client.ps1` es genérico (host/puerto/password/comando como parámetros) y se reutiliza
tal cual para A y B — no se creó un segundo cliente RCON duplicado.

### Velocity — datos operativos (FASE 3)

```text
VELOCITY_STATUS      = CERTIFIED
PORT                 = 25565 (bind 127.0.0.1 — solo local, playit.gg todavía no configurado)
JAVA                 = 25 (E:\minecraft-hardcore\tools\java25\jdk-25.0.4.1+1\bin\java.exe)
VELOCITY_VERSION     = 4.2.0 (build 30)
FORWARDING_MODE      = modern
FORWARDING_SECRET    = proxy/velocity/forwarding.secret (gitignored, auto-generado por Velocity; el mismo
                       valor está copiado en server-a/config/FabricProxy-Lite.toml y server-b/... , también
                       gitignored)
BACKENDS_REGISTERED  = server-a (127.0.0.1:25566), server-b (127.0.0.1:25567)
INITIAL_TRY          = server-a
ONLINE_MODE          = false (FASE 8, a pedido del usuario - permite clientes no-premium/"pirata")
WHITELIST            = activa y forzada en A y B (white-list=true, enforce-whitelist=true); UUIDs offline
                       calculados con UUID.nameUUIDFromBytes("OfflinePlayer:<username>"), el mismo algoritmo
                       exacto que usa Velocity internamente (UuidUtils.generateOfflinePlayerUuid, verificado
                       por bytecode) y que vanilla usa en modo offline
DIFFICULTY           = hard en A y B (server.properties, persistente a través de cualquier recycle futuro)
```

**Arrancar:** `powershell -File scripts\windows\start-velocity.ps1` (usa siempre `$Java25Exe`, se niega a
arrancar si 25565 ya está ocupado, corre en primer plano).

**Detener:** Velocity no tiene RCON ni consola remota — la forma limpia es escribir `end` directamente en su
propia consola (foreground). `scripts\windows\stop-velocity.ps1` es el respaldo para un stop no interactivo:
identifica el PID exacto vinculado a :25565 por command line (nunca A/B, nunca `taskkill /IM java.exe`) y
requiere `-Force` explícito porque **no** es un shutdown gracioso (limitación documentada, no un descuido).

**Conectar como cliente:** Minecraft 1.20.1 → "Direct Connect" → `localhost:25565`. Entras en `server-a`;
usa `/server server-b` / `/server server-a` para cambiar de backend sin desconectarte — certificado con 6
transferencias estables en FASE 3.

**No instalado:** FabricProxy-Lite en Velocity (no aplica, es un mod de servidor), CrossStitch (no
necesario todavía — solo Fabric API + FabricProxy-Lite en los backends), ningún plugin adicional de Velocity.

### HardcoreCoordinator — plugin Velocity propio (FASE 4-8)

```text
PLUGIN_STATUS   = CERTIFIED (FASE 4-7) / PARTIAL (FASE 8 - ver README "Estado actual")
PLUGIN_VERSION  = 0.5.0
PROJECT_PATH    = velocity-plugin/hardcore-coordinator/  (Java 25, Gradle 9.7.1 vía wrapper)
DEPLOYED_JAR    = proxy/velocity/plugins/hardcore-coordinator-0.5.0.jar  (gitignored, build reproducible)
CONFIG          = proxy/velocity/plugins/hardcore-coordinator/config.properties  (gitignored; ver
                  velocity-plugin/hardcore-coordinator/config.properties.template para el formato)
STATE           = proxy/velocity/plugins/hardcore-coordinator/state.json  (gitignored, escritura atómica
                  temp+replace con .bak; runId/active/phase/activeRunStartedAt/generation/seed/pendingDeath;
                  sobrevive reinicios de Velocity; lee también el formato antiguo de FASE 4-6 sin campos
                  de muerte, con valores por defecto)
COMMANDS        = /hs status, /hs switch
PERMISSION      = consola siempre permitida; jugadores solo si su username está en `admins=` del config
                  (no hay LuckPerms todavía)
```

**Build:** `powershell -File scripts\windows\build-hardcore-coordinator.ps1` (`gradlew clean build`, Java 25
canónico vía `JAVA_HOME` de proceso — nunca modifica el entorno global; corre los tests unitarios).

**Deploy:** `powershell -File scripts\windows\deploy-hardcore-coordinator.ps1` (copia únicamente el jar
construido a `proxy/velocity/plugins/`, elimina versiones previas). Requiere detener Velocity primero (el
jar viejo queda bloqueado mientras Velocity lo tiene cargado) y reiniciarlo después; A y B no se tocan.

**Diseño (FASE 4):** `SwitchService` transfiere jugadores vía `CompletableFuture`/
`Player.createConnectionRequest(...)` — nunca bloquea el hilo que lo invoca, nunca manipula paquetes
Minecraft directamente. `HsCommand` decide el destino automáticamente.

**Diseño (FASE 5):** el modelo ACTIVE/STANDBY vive en `StateManager` (`CoordinatorState` inmutable,
`generation`/`seed`/`runId` por backend), persistido en `state.json` tras cada transición. Un switch solo
puede apuntar a un backend `READY` — nunca a uno `RECYCLING`/`DRAINING`/`STARTING`/`FAILED`. Tras un switch
exitoso, el backend anterior pasa a `DRAINING`→`RECYCLING` y `RecycleService` invoca
`scripts/windows/recycle-backend.ps1` como proceso externo, en un executor dedicado (nunca el hilo
principal de Velocity) — el jugador sigue jugando de inmediato, el recycle corre en segundo plano.

**Bug real encontrado y corregido en vivo:** la primera versión de `RecycleService` leía el stdout del
proceso hijo mediante un pipe de Java — eso se colgaba indefinidamente, porque el backend recién arrancado
por el script (que vive para siempre, ese es su propósito) hereda en Windows un handle duplicado del mismo
pipe, así que este nunca ve EOF. Solucionado redirigiendo la salida a un archivo
(`logs/recycle/<operationId>.log`) en vez de leerla por pipe. Ver `git log` para el detalle.

**Diseño FASE 7 — trigger de muerte global:** `HardcoreCoordinator` añade una máquina de estados `RunPhase`
(`ACTIVE → ENDING → WAITING_FOR_STANDBY|SWITCHING → ACTIVE`) por encima del estado por-backend existente.
`DeathCoordinator` hace polling (cada 100ms, vía `server.getScheduler()`) de la carpeta `inbox/` del spool
de muertes escrito por `HardcoreDeathSignal` (ver sección siguiente). Por cada evento: valida `eventId`
duplicado, backend desconocido, backend no-activo, timestamp anterior al inicio del run activo (evento
obsoleto de un run ya perdido) y `phase != ACTIVE` (ya hay una muerte en curso) — solo si pasa todas las
validaciones marca `phase=ENDING`, persiste `PendingDeath` en `state.json` y dispara el mismo
`SwitchService.switchNow()` ya certificado en FASE 4 (nunca un mecanismo nuevo). Si el standby todavía no
está `READY` (recycle en curso), pasa a `WAITING_FOR_STANDBY` en vez de perder la muerte; `SwitchService`
notifica a `DeathCoordinator` cuando el recycle del backend standby termina, y si la fase seguía
`WAITING_FOR_STANDBY` para ese backend, reintenta el switch automáticamente — sin intervención manual.
Este caso límite (muerte durante recycle del standby) se verificó en vivo dos veces con evidencia cruzada
de logs de Velocity y del backend. Se corrigió además un problema latente de enrutamiento: el `try=[...]`
estático de `velocity.toml` no conocía el backend ACTIVO dinámico del plugin, así que se añadió un handler
de `PlayerChooseInitialServerEvent` que enruta toda conexión nueva/reconexión al backend activo real.

**Diseño FASE 8 — UX mínima y fix de transferencia N-jugadores:** `RunUx` (clase nueva, sin estado) envía
título + chat vía la API de Adventure real de Velocity (`Player.sendMessage`/`showTitle`/`Title.title`,
verificada con `javap` contra el `velocity-4.2.0-30.jar` en ejecución, no copiada de tutoriales de otra
versión) — "RUN LOST" al aceptar la muerte, "Preparing next run..." si toca esperar al standby, "RUN #N -
New world started" al completar el switch, cada uno una sola vez por transición. Cada envío está envuelto en
try/catch: un fallo de UX solo genera un warning en el log, nunca afecta el switch/recycle real (spec
sección 23). **Bug real encontrado y corregido en vivo** (con 2 jugadores conectados a la vez):
`SwitchService.doTransfer()` transfería a todos los jugadores del backend perdedor en paralelo
(`Stream.map` + `CompletableFuture.allOf`); al hacerlo, dos logins simultáneos al mismo backend recién
arrancado dispararon una `ConcurrentModificationException` real dentro del propio Minecraft/Fabric (una
caché de codificación de registro con un `HashMap.computeIfAbsent` no thread-safe) — confirmado leyendo el
log del propio backend, no adivinado. Velocity reportó eso como `SERVER_DISCONNECTED`, la transferencia
del segundo jugador falló, el switch entero se abortó como `SWITCH_PARTIAL_FAILURE`, y como nunca se llegó
a `commitSwitch()`, tampoco se disparó ningún recycle — el jugador fallido quedó atrapado en `SPECTATOR`
(por la contención de FASE 8, ver abajo) en un backend que el coordinador seguía creyendo "activo" pero que
ya no tenía forma de reintentar, porque el único disparador de reintento (`onBackendRecycled`) depende de
que termine un recycle que nunca se inició. **Fix (0.5.0)**: `doTransfer()` ahora encadena las
transferencias secuencialmente (una `CompletableFuture` a la vez, nunca en paralelo) — evita la carrera del
motor para cualquier N, no solo 2. Además, `DeathCoordinator.pollOnce()` ahora reintenta
`attemptDeathSwitch()` en cada poll (100ms) mientras la fase sea `WAITING_FOR_STANDBY`, en vez de depender
exclusivamente del evento de recycle completado — así un fallo de transferencia por cualquier otra causa
futura tampoco puede dejar el run atascado para siempre. Reutiliza `switchNow()` sin ningún mecanismo
nuevo.

### HardcoreDeathSignal — mod Fabric propio (FASE 7-8)

```text
MOD_STATUS      = CERTIFIED
MOD_VERSION     = 0.2.0
PROJECT_PATH    = fabric-mods/hardcore-death-signal/  (Fabric Loom 1.17.20 vía wrapper; Java 21 SOLO
                  como herramienta de build, el bytecode compilado apunta a release 17)
MAPPINGS        = loom.officialMojangMappings()  (no Yarn — nombres de clase/método Mojang)
TARGET          = Minecraft 1.20.1, Fabric Loader 0.19.5, Fabric API 0.92.12+1.20.1
ENTRYPOINT      = com.hardcoreseamless.deathsignal.HardcoreDeathSignalMod (ModInitializer, server-only)
DEPLOYED_JAR    = server-a/mods/hardcore-death-signal-0.2.0.jar y server-b/mods/... (idéntico, gitignored)
SYSTEM_PROPS    = -Dhardcore.backendId=server-a|server-b  (requerido; el mod se autodeshabilita con
                  error de log si falta)
                  -Dhardcore.deathSpoolDir=<ruta>  (opcional; por defecto
                  E:\minecraft-hardcore\runtime\death-events)
```

**Diseño:** el mod es deliberadamente un sensor puro — no contiene ninguna lógica de switching. Se
suscribe a `ServerLivingEntityEvents.AFTER_DEATH` (evento no cancelable, dispara después de una muerte
real, a diferencia de `ALLOW_DEATH` que es un pre-check cancelable). Filtra por `instanceof ServerPlayer`,
construye un `DeathEvent` (backend, timestamp, UUID/nombre del jugador, dimensión, coordenadas,
`damageSource.getMsgId()`, semilla del mundo) y lo escribe como JSON en `<spoolDir>/inbox/` usando el
patrón atómico ya establecido en el proyecto: escribe a `<eventId>.tmp` y luego
`Files.move(..., ATOMIC_MOVE)` al nombre final. `HardcoreCoordinator` (proceso Velocity, ver sección
anterior) consume esos archivos por polling y mueve cada uno a `processed/` (válido) o `invalid/`
(rechazado) tras procesarlo — el mod nunca borra ni modifica sus propios eventos una vez escritos.

**Diseño FASE 8 — contención local de run perdida:** el mod añade `localRunEnded` (un `boolean volatile`,
puramente en memoria de esa JVM, nunca persistido a disco — muere con el proceso, así que una JVM nueva tras
un recycle siempre arranca en `false`, ver spec sección 16). En el primer `AFTER_DEATH` real de un jugador
en esa JVM: pone `localRunEnded=true` y fuerza `GameType.SPECTATOR` a **todos** los jugadores conectados a
ese backend en ese momento (no solo a quien murió — la semántica es "toda la run se perdió", no "murió un
jugador", spec sección 19). Además se suscribe a `ServerPlayerEvents.AFTER_RESPAWN` (re-bloquea a
`SPECTATOR` si alguien pulsa Respawn mientras `localRunEnded=true`) y a `ServerPlayConnectionEvents.JOIN`
(por si alguien se conecta tarde a un backend ya perdido). Ningún estado de esto se filtra a la siguiente
generación: es exclusivamente en memoria de una JVM que va a ser detenida y su world borrado.

### HardcoreHud — mod Fabric propio (FASE 9)

```text
MOD_STATUS      = READY_FOR_USER_VALIDATION
MOD_VERSION     = 0.4.0
PROJECT_PATH    = fabric-mods/hardcore-hud/  (mismo toolchain que hardcore-death-signal: Fabric Loom
                  1.17.20, Java 21 SOLO como build-tool, bytecode compilado a release 17)
MAPPINGS        = loom.officialMojangMappings()
TARGET          = Minecraft 1.20.1, Fabric Loader 0.19.5, Fabric API 0.92.12+1.20.1
ENTRYPOINT      = com.hardcoreseamless.hud.HardcoreHudMod (ModInitializer, server-only, ningún mod
                  de cliente requerido)
DEPLOYED_JAR    = server-a/mods/hardcore-hud-0.5.0.jar y server-b/mods/... (idéntico, aún no compilado/desplegado)
```

**Por qué un mod nuevo y no reutilizar HardcoreDeathSignal:** el spec de FASE 9 (sección 6.3) pide
explícitamente no convertir el sensor de muerte en un mod multipropósito. `HardcoreHud` no se suscribe a
`AFTER_DEATH` ni a ningún evento del pipeline de muerte/switch.

**TAB hearts:** `setupTabHealth()` corre una vez por arranque de JVM. Es deliberadamente idempotente
(`scoreboard.getObjective(name) == null` antes de crear) porque tras un recycle la JVM es nueva pero el
código es el mismo — sin este chequeo, cada generación nueva simplemente recrea el objetivo desde cero, que
es exactamente el comportamiento deseado (spec sección 6.2). Usa la API vanilla directamente
(`Scoreboard.addObjective(name, ObjectiveCriteria.HEALTH, displayName, ObjectiveCriteria.RenderType.HEARTS)`
+ `setDisplayObjective(Scoreboard.DISPLAY_SLOT_LIST, objective)`), verificada con `javap` contra el jar
real — no un comando de texto ejecutado vía dispatcher.

**Brújula — retirada (2026-09-22):** pasó por tres iteraciones de feedback directo del usuario (v0.1.0
flecha de texto al compañero más cercano; v0.2.0 punto gráfico sobre la barra de XP vía mod de **cliente**,
retirado tras un error de instalación reportado por el usuario; v0.3.0 flechas de colores por compañero,
100% servidor) antes de que el usuario indicara que ya tiene otro mod que cubre esta función. Se eliminó
por completo de `HardcoreHudMod` (`onEndTick`, `updateCompassFor`, `sendColoredArrows`,
`sendOtherDimensionIndicator` y constantes asociadas) — no queda mod de cliente ni lógica de brújula en el
proyecto.

**Contador de muertes/runs (sidebar):** `hc_deaths` es un objetivo `dummy` (no `health`, para no acoplar
esta feature al de TAB hearts), mostrado en `Scoreboard.DISPLAY_SLOT_SIDEBAR` — el panel discreto más
cercano a "esquina, sin abrir menús" que vanilla ofrece sin un mod de cliente, aunque su posición real es
**arriba-a-la-derecha**, no arriba-a-la-izquierda (documentado explícitamente, no asumido como equivalente
a lo pedido). Cada ~1s (`DEATH_COUNTER_INTERVAL_TICKS=20`): lee `runId` de `state.json` de
`HardcoreCoordinator` (proceso Velocity, una JVM distinta) con una regex mínima
(`"runId"\s*:\s*(-?\d+)`) — sin librería JSON nueva, sin escribir nunca ese archivo, solo lectura para
mostrar un número (spec sección 4: no tocar el ciclo de vida real de `runId`) — y actualiza el título del
objetivo a `☠ Runs Lost: N`; sincroniza además una fila con score `0` por cada jugador conectado *ahora*
a esa JVM, quitando cualquier fila de alguien que ya no esté (nunca deja un nombre colgado tras un
switch/desconexión). Si `state.json` no se puede leer (ruta distinta, proceso caído), muestra `N = ?` y
registra el warning una sola vez, nunca por tick.

**Casos límite cubiertos por construcción, sin código especial:** el bootstrap de TAB hearts nunca lee
`state.json` ni sabe nada de A/B/switch — simplemente itera sobre quien esté conectado a *esa* JVM en
*ese* tick (el contador de muertes es la única excepción, y solo en modo lectura). Un switch, un recycle,
una desconexión, o una reconexión cambian quién está en `server.getPlayerList().getPlayers()` entre un
tick y el siguiente, y el HUD se recalcula automáticamente en el próximo ciclo sin reiniciar el cliente ni
requerir ninguna integración con `SwitchService`/`DeathCoordinator` (spec sección 5.5).

## Corazones Hardcore — matriz de comparación (FASE 9, sección 7.3 del spec)

| Criterio                       | `hardcore=true`                                          | Resource pack visual (elegido)                        |
|---------------------------------|-----------------------------------------------------------|---------------------------------------------------------|
| Corazones Hardcore               | Sí, nativo, cero mantenimiento de assets                  | Sí, textura verificada (SHA-1/SHA-256 + `pack.mcmeta`)   |
| Interfiere con death pipeline    | Sí — vanilla fuerza `SPECTATOR` solo al jugador que murió en un servidor dedicado (no borra el mundo, no detiene el servidor); modelo "muerte individual" que se solapa parcialmente con `localRunEnded`/contención de FASE 8 | No — es puramente textura, cero lógica de servidor        |
| Compatible con Velocity          | Sí (no relacionado)                                        | Sí (no relacionado)                                      |
| Compatible con A/B recycle       | Sí, pero el flag debe fijarse igual en A y B `server.properties` | Sí, mismo `resource-pack`/`resource-pack-sha1` en A y B |
| Compatible con Nether/End        | Sí (no relacionado con dimensión)                          | Sí (no relacionado con dimensión)                        |
| Requiere cambios servidor        | Un flag (`hardcore=true`) en A y B                          | 3 líneas en `server.properties` de A y B                |
| Requiere cambios cliente         | Ninguno                                                    | Descarga automática del pack (URL+hash), sin instalación manual |
| Riesgo                          | Medio — segunda semántica de muerte parcialmente redundante/conflictiva con el sistema propio | Bajo — no toca lógica de juego                           |
| Mantenimiento                   | Ninguno                                                    | Bajo — un ZIP versionado externamente, hash fijado       |

```text
HARDCORE_MODE_RECOMMENDATION = hardcore=false + HardcoreVisuals-1.20.1.zip (Opción B)
```

**Evidencia para la decisión:** en un servidor dedicado (no singleplayer), `hardcore=true` no termina la
partida ni borra el mundo cuando un jugador muere — solo fuerza a **ese** jugador a `GameType.SPECTATOR`
(el mundo y los demás jugadores siguen normalmente). Ese es exactamente el modelo de "muerte individual"
que este proyecto reemplaza deliberadamente por "una muerte = toda la run compartida se pierde para todos"
(FASE 7-8). Activar `hardcore=true` no aportaría ninguna capacidad que el proyecto no tenga ya
(`HardcoreDeathSignal` + `HardcoreCoordinator` + contención local), y sí introduciría una segunda autoridad
de "qué pasa al morir" corriendo en paralelo a la propia, con riesgo real de comportamiento confuso
(un jugador podría terminar en spectator por la razón vanilla justo cuando también debería estarlo por la
razón del proyecto, o viceversa en el orden de eventos). La hipótesis del proyecto (spec sección 7.4) queda
confirmada por evidencia, no asumida.
