# VERSION_LOCK

Fuente de verdad para las versiones fijadas del proyecto **Minecraft Hardcore Seamless**.

No cambiar ninguna versión LOCKED sin autorización explícita del usuario. Si aparece una incompatibilidad,
aplicar la REGLA DE PARADA (ver `ARCHITECTURE.md`) y reportar `COMPATIBILITY_BLOCKER_FOUND = YES` en vez de improvisar.

| Componente                  | Versión                              | Estado  | Fuente / Evidencia |
|------------------------------|---------------------------------------|---------|---------------------|
| Minecraft                   | 1.20.1                                | LOCKED  | Fijado por especificación de FASE 0 |
| Java — backends Fabric (A/B) | 17 (Eclipse Temurin 17.0.20.1+1)     | LOCKED  | Instalado localmente en `tools/java17/jdk-17.0.20.1+1/` (zip portable, checksum SHA-256 verificado contra Adoptium API). No es la `java` global de la máquina — ver `Java runtimes por componente` abajo |
| Java — proxy Velocity        | 25 (Eclipse Temurin 25.0.4.1+1)      | LOCKED  | Instalado localmente en `tools/java25/jdk-25.0.4.1+1/` (zip portable, checksum SHA-256 verificado contra Adoptium API). Requisito oficial: `docs.papermc.io/velocity/getting-started/` → "Velocity requires at least Java 25." |
| Fabric Loader               | 0.19.5                                | LOCKED  | Verificado en `https://meta.fabricmc.net/v2/versions/loader` — build 5, `stable: true`. Server launcher jar confirmado disponible para MC 1.20.1 + loader 0.19.5 (`/v2/versions/loader/1.20.1/0.19.5/1.0.1/server/jar` → HTTP 200) |
| Fabric API                  | 0.92.12+1.20.1                        | LOCKED  | Verificado en Modrinth API (`api.modrinth.com/v2/project/fabric-api/version`), última release listada para `game_versions=["1.20.1"]`, publicada 2026-09-01. Instalado en `server-a/mods/fabric-api-0.92.12+1.20.1.jar`, SHA-256 `4197ff4fbdac13cffccd267c1bc59e9fbabb2b5683a9d5f8023f4b5ea16a1c1e` (SHA-1/SHA-512 también verificados contra Modrinth antes de copiar al mods folder) |
| Velocity                    | 4.2.0 (build 30)                      | LOCKED  | Instalado en `proxy/velocity/velocity-4.2.0-30.jar`, descargado y checksum-verificado contra `fill.papermc.io/v3/projects/velocity` (SHA-256 `35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8`). Soporte general de protocolo "1.7.2 through 26.2" según `docs.papermc.io/velocity/server-compatibility/`, que cubre 1.20.1 |
| Fabric proxy forwarding mod | FabricProxy-Lite 2.6.0                | LOCKED  | Instalado en `server-a/mods/` y `server-b/mods/`, hash SHA-1/SHA-512 idéntico verificado contra Modrinth API en ambos (SHA-1 `4953b0c78fb6556b537f5bad93099c212b5e9f18`). Recomendado explícitamente por la documentación oficial de Velocity para servidores Fabric ("Velocity works with Fabric out of the box... add support for player info forwarding using a mod like FabricProxy-Lite") |
| Fabulously Optimized        | 5.4.1 (para 1.20.1)                   | LOCKED  | Verificado en Modrinth API, última versión listada para `game_versions=["1.20.1"]` (no hay versiones más recientes del pack para 1.20.1; el desarrollo del pack continuó en versiones de MC posteriores) |
| playit.gg                   | externo                               | FUTURE  | No configurado en esta fase (ver sección 6 del prompt de FASE 0) |
| velocity-api (plugin dev)   | 4.2.0                                 | LOCKED  | Exactamente la misma versión que el Velocity en ejecución (`repo.papermc.io` maven-metadata: `<release>4.2.0</release>`). Real API verificada vía `javap` sobre el jar descargado, no copiada de ejemplos de versiones antiguas (ver sección 25 del prompt de FASE 4) |
| Gradle                      | 9.7.1                                 | LOCKED  | Instalado localmente en `tools/gradle/gradle-9.7.1/` (zip portable, checksum SHA-256 verificado). Primera versión con soporte de daemon en Java 25 es 9.1.0; 9.7.1 es la última estable |
| Guice (compileOnly)         | 7.0.0                                 | LOCKED  | Solo para la anotación `@Inject` en tiempo de compilación — confirmado que el jar real de Velocity ya empaqueta Guice (`com/google/inject/Inject.class` presente dentro de `velocity-4.2.0-30.jar`), así que en runtime se usa el Guice de Velocity, no esta dependencia |
| slf4j-api (compileOnly)     | 2.0.16                                | LOCKED  | Igual que Guice: solo para el tipo `Logger` en compilación; confirmado `org/slf4j/Logger.class` presente en el jar real de Velocity para runtime |
| JUnit Jupiter                | 5.11.0 (BOM)                          | LOCKED  | Tests unitarios del plugin (`SwitchServiceTest`) |
| Mockito                     | 5.23.0                                | LOCKED  | Mockea las interfaces de Velocity (20-30+ métodos abstractos cada una) en vez de implementarlas a mano — ver sección 26 del prompt de FASE 4 |

## Notas de compatibilidad

- **Velocity + Forge**: la documentación oficial de Velocity indica que el soporte de Forge para versiones
  intermedias entre 1.13 y 1.20.1 "no está planeado". Esto **no aplica a este proyecto**, que usa Fabric
  (no Forge) con FabricProxy-Lite para el forwarding moderno de Velocity. El soporte general de protocolo de
  Velocity (1.7.2–26.2) cubre 1.20.1 sin problema, y el soporte de Fabric está confirmado "out of the box".
- **Fabric Loader vs Fabric API vs FabricProxy-Lite**: FabricProxy-Lite 2.6.0 declara Fabric API como
  dependencia embebida; no se detectó conflicto de versiones entre Loader 0.19.5, Fabric API 0.92.12+1.20.1
  y FabricProxy-Lite 2.6.0.
- **Fabulously Optimized** es un modpack de CLIENTE. Internamente puede fijar su propia versión de Fabric
  Loader para el cliente (distinta de la del servidor); esto es aceptable porque FO se ejecuta en una JVM
  de cliente separada y no necesita coincidir con el Loader del servidor. No se mezcla con `shared/server-mods`.

## Java runtimes por componente

Esta máquina necesita **tres** runtimes Java distintos simultáneamente, ninguno de los cuales es la `java`
global del PATH. Ningún script de este proyecto debe depender de la `java` global ni de `JAVA_HOME`.

```text
FABRIC_BACKEND_JAVA   = 17   (Server A, Server B)
VELOCITY_JAVA         = 25   (proxy)
```

| Runtime | Versión | Estado | Ruta absoluta |
|---|---|---|---|
| Java 8 (preexistente, ajena al proyecto) | 1.8.0_461 | sin tocar | `C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java.exe` — sigue siendo la `java` por defecto del PATH del sistema; esto es intencional y no afecta al proyecto porque ningún script del proyecto usa `java` a secas |
| Java 21 (preexistente, ajena al proyecto) | 21.0.12.1 (Temurin) | sin tocar | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` — **no se usa como sustituto de Java 17 para los backends Fabric**, por instrucción explícita del usuario |
| **Java 17 (Fabric backends A/B)** | 17.0.20.1+1 (Temurin) | **INSTALADO** 2026-09-14 | `E:\minecraft-hardcore\tools\java17\jdk-17.0.20.1+1\bin\java.exe` |
| **Java 25 (Velocity)** | 25.0.4.1+1 (Temurin) | **INSTALADO** 2026-09-14 | `E:\minecraft-hardcore\tools\java25\jdk-25.0.4.1+1\bin\java.exe` |

Instalación de Java 17 y 25: descarga directa de los zips portables oficiales de Eclipse Temurin (`adoptium.net`/
GitHub releases `adoptium/temurin{17,25}-binaries`), **no** el instalador `.msi` (para no requerir privilegios
de administrador ni tocar el PATH/registro del sistema). SHA-256 de cada zip verificado contra el publicado
oficialmente antes de extraer (Java 17: `e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0`;
Java 25: `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`). Extraídos en `tools/java{17,25}/`
(excluidos de git vía `.gitignore`). Ambos verificados con `java.exe -version` mostrando la versión esperada.

Los scripts de arranque de Server A / Server B / Velocity deben `. "$PSScriptRoot\java-paths.ps1"` (ver
`scripts/windows/java-paths.ps1`, con `Assert-Java17`/`Assert-Java25`) y usar `$Java17Exe`/`$Java25Exe`
explícitamente — nunca `java` a secas. Confirmado en vivo (FASE 3): los tres procesos (Server A, Server B,
Velocity) corriendo simultáneamente, cada uno con su ExecutablePath verificado vía `Win32_Process`, cada uno
en su Java correcto.

## ISSUES detectados en FASE 0 (todos resueltos)

- **JAVA_17_NOT_INSTALLED** — RESUELTO 2026-09-14. Instalado localmente en `tools/java17/`, ver tabla arriba.
  No se modificó ninguna variable de entorno global; Java 8 sigue siendo la `java` por defecto del sistema
  y Java 21 permanece instalado sin usarse para los backends Fabric.
- **VELOCITY_REQUIRES_JAVA_25** — detectado 2026-09-14, RESUELTO en FASE 3 (Java 25 instalado, ver tabla arriba).

## Hallazgos de FASE 3

- **DIRECT_BACKEND_JOIN = ACCEPTED** (probado 2026-09-14): con `online-mode=true` + FabricProxy-Lite
  `hackOnlineMode=true` en A/B, un cliente puede conectarse directamente a `localhost:25566`/`:25567` sin
  pasar por Velocity — FabricProxy-Lite gestiona el forwarding de identidad para conexiones *proxied*, pero
  no añade por sí mismo un firewall que bloquee conexiones *no proxied* en la misma máquina. No es un fallo
  de FASE 3 (regla explícita: "no declarar automáticamente FAIL"). Mitigación recomendada antes de exponer
  el proxy públicamente (FASE 5 / playit.gg): `server-ip=127.0.0.1` en `server.properties` de A y B (sección
  18 del prompt de FASE 3) — esto no bloqueará conexiones directas *desde la misma máquina* (que seguirán
  aceptándose vía loopback), pero sí impedirá que un cliente externo llegue a A/B sin pasar por Velocity una
  vez el proxy esté expuesto. Pendiente de aplicar y revalidar; no bloquea el cierre de FASE 3.

## Hallazgos de FASE 5

- **hardcore-coordinator 0.2.0**: bump de versión (era 0.1.0 en FASE 4) al añadir el modelo
  ACTIVE/READY/DRAINING/RECYCLING/FAILED + recycle automático. `build-hardcore-coordinator.ps1` ahora corre
  `gradlew clean build` (no solo `build`) porque Gradle no borra jars de versiones anteriores en
  `build/libs/` cuando solo cambia el número de versión — se detectó un jar `0.1.0` obsoleto conviviendo con
  el `0.2.0` recién construido.
- **Bug real, encontrado y corregido en vivo (Windows handle inheritance)**: la primera versión de
  `RecycleService` leía la salida de `recycle-backend.ps1` mediante un pipe Java mantenido con
  `ProcessBuilder`. Colgaba indefinidamente en la práctica: el script arranca el nuevo JVM del backend vía
  `Start-Process`, y en Windows ese proceso nieto hereda un handle duplicado del extremo de escritura del
  mismo pipe que Java estaba leyendo — como el backend vive indefinidamente (ese es su propósito), el pipe
  nunca ve EOF, así que el callback de finalización del recycle nunca se disparaba (`state.json` se quedaba
  bloqueado en `RECYCLING` para siempre). Solucionado redirigiendo la salida del proceso a un archivo
  (`logs/recycle/<operationId>.log`, leído después con `Process.waitFor()`, que sí depende solo del handle
  del proceso y no se ve afectado) en vez de leerla por pipe. Verificado con re-test en vivo dos veces
  (recycle de A y de B) tras el fix.

## Hallazgos de FASE 7

- **hardcore-coordinator 0.3.0**: bump de versión (era 0.2.0 en FASE 5) al añadir `RunPhase`,
  `DeathCoordinator`, `PendingDeath` y el handler de `PlayerChooseInitialServerEvent`.
- **hardcore-death-signal 0.1.0** (mod Fabric nuevo): `fabric-mods/hardcore-death-signal/`.
- **ID correcto del plugin de Gradle Fabric Loom**: el template oficial `fabric-example-mod` (rama
  `1.20.1` en GitHub) especifica `id 'net.fabricmc.fabric-loom-remap' version "${loom_version}"`, que no
  existe en ningún repositorio Maven resoluble — verificado directamente contra
  `maven.fabricmc.net/fabric-loom/fabric-loom.gradle.plugin/maven-metadata.xml` (404/vacío). El ID real es
  `fabric-loom`. Se usó `loom_version=1.17.20` (última estable, en vez del `1.17-SNAPSHOT` del template).
- **Java 21 usado exclusivamente como build-tool para Fabric Loom** (no como runtime de backend): Fabric
  Loom 1.17.20 requiere JVM 21+ para ejecutar Gradle/Loom (`Dependency requires at least JVM runtime
  version 21`), incompatible con el Java 17 local del proyecto usado hasta ahora para builds. Se usó el
  Java 21 preexistente en la máquina (`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`)
  **únicamente** para invocar `gradlew`, nunca para ejecutar Server A/B. El bytecode compilado del mod
  sigue apuntando a Java 17 (`tasks.withType(JavaCompile) { it.options.release = 17 }`), y los backends
  siguen arrancando exclusivamente con `tools/java17/`. Esto no viola la regla permanente de no sustituir
  Java 17 por Java 21 en los backends — es uso de build-tooling, disclosed explícitamente.
- **Mappings**: el build usa `loom.officialMojangMappings()` (no Yarn). Nombres de clase/método reales
  verificados con `javap` contra el jar remapeado en caché de Loom
  (`.gradle/loom-cache/minecraftMaven/.../minecraft-merged-...jar`): `net.minecraft.server.level.ServerPlayer`,
  `net.minecraft.server.level.ServerLevel`, `net.minecraft.world.entity.LivingEntity`,
  `net.minecraft.world.damagesource.DamageSource`, métodos `getStringUUID()`, `serverLevel()`,
  `dimension()`, `getSeed()`, `getMsgId()`. El primer intento usó nombres Yarn
  (`net.minecraft.entity.LivingEntity`, etc.) y falló con 8 errores de compilación.
- **Gap de enrutamiento corregido (no un bug de FASE 7, pero descubierto en ella)**: `velocity.toml` define
  `try = ["server-a"]` de forma estática — Velocity usa esa lista para el enrutamiento inicial de
  conexión, pero no conoce el backend ACTIVE dinámico que rastrea el plugin. Nunca se manifestó como fallo
  en FASE 4-6 porque el mismo jugador permaneció conectado continuamente durante esas pruebas. Verificado
  con `javap` que `PlayerChooseInitialServerEvent` existe y expone `setInitialServer(RegisteredServer)`;
  se añadió el handler en `HardcoreCoordinatorPlugin` para enrutar toda conexión nueva/reconexión al
  `active` real del `state.json`.
- **java.util.Properties y backslashes en paths de Windows**: riesgo identificado (no un bug real, evitado
  antes de que ocurriera) — `Properties.load()` trata `\` como carácter de escape, y una secuencia no
  reconocida (`\m`, `\r`, `\d`, etc.) se descarta silenciosamente en vez de fallar. Se evitó usando
  forward slashes en `deathSpoolDir` (`E:/minecraft-hardcore/runtime/death-events`) tanto en el config
  template como en el valor por defecto del código — NIO acepta `/` sin problema en Windows.

## Hallazgos de FASE 8 (PARTIAL - checklist formal de verificación aún pendiente)

- **hardcore-death-signal 0.2.0**: bump de versión (era 0.1.0 en FASE 7) al añadir la contención local de
  run perdida (`localRunEnded`, `SPECTATOR` forzado, `ServerPlayerEvents.AFTER_RESPAWN`,
  `ServerPlayConnectionEvents.JOIN`). APIs verificadas con `javap` contra el jar real de
  `fabric-entity-events-v1`/`fabric-networking-api-v1` (extraídos del `fabric-api-0.92.12+1.20.1.jar`
  real, no asumidos): `ServerPlayerEvents.AFTER_RESPAWN` → `afterRespawn(ServerPlayer, ServerPlayer,
  boolean)`, `ServerPlayConnectionEvents.JOIN` → `onPlayReady(ServerGamePacketListenerImpl, PacketSender,
  MinecraftServer)`. `ServerPlayer.setGameMode(GameType)` y `GameType.SPECTATOR` verificados igual contra
  el jar remapeado de Loom.
- **hardcore-coordinator 0.3.0 → 0.4.0 → 0.5.0**: 0.4.0 añadió `RunUx` (título/chat vía Adventure).
  Adventure API real verificada con `javap` contra `proxy/velocity/velocity-4.2.0-30.jar` en ejecución
  (las clases de Adventure están shadeadas dentro del jar de Velocity, sin marcador de versión propio, así
  que se verificó directamente el bytecode real en vez de asumir una versión de `net.kyori:adventure-api`
  del repositorio Maven): `Audience.sendMessage(Component)`, `Audience.showTitle(Title)`,
  `Title.title(Component, Component)`, `Component.text(String, TextColor)`,
  `NamedTextColor.{RED,GOLD,GREEN,GRAY,DARK_RED}`.
- **Bug real, encontrado y corregido en vivo (carrera de registro de Minecraft/Fabric bajo logins
  simultáneos)**: con 2 jugadores conectados, un switch dejó a uno de ellos atrapado en `SPECTATOR` en el
  backend perdedor. Diagnosticado con evidencia directa del log del backend afectado —
  `java.util.ConcurrentModificationException` en `HashMap.computeIfAbsent`
  (`net.minecraft.class_6903$1.method_46623`, dentro de la codificación de un paquete de login) disparada
  por dos conexiones simultáneas al mismo backend recién arrancado. `SwitchService.doTransfer()`
  transfería a todos los jugadores del backend perdedor en paralelo (`CompletableFuture.allOf`); la
  segunda transferencia falló con `SERVER_DISCONNECTED`, el switch quedó en `SWITCH_PARTIAL_FAILURE` sin
  llegar nunca a `commitSwitch()`/`beginRecycle()`, y el reintento automático (`onBackendRecycled()`)
  nunca se disparó porque no había ningún recycle en curso que completar. **hardcore-coordinator 0.5.0**
  corrige ambas mitades: `doTransfer()` ahora transfiere jugadores secuencialmente (una
  `CompletableFuture` a la vez, nunca en paralelo — corrección N-jugadores-segura, no solo para 2), y
  `DeathCoordinator.pollOnce()` reintenta `attemptDeathSwitch()` en cada poll (100ms) mientras la fase sea
  `WAITING_FOR_STANDBY`, sin depender exclusivamente del evento de recycle completado. Verificado en vivo
  tras el fix: reconexión limpia de ambos jugadores, estado recuperado correctamente.
- **UUIDs offline verificados con dos implementaciones independientes**: para poblar `whitelist.json` con
  UUIDs válidos antes de que los jugadores offline se conectaran por primera vez, se calculó
  `UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(UTF_8))` a mano. El algoritmo exacto se
  confirmó desensamblando `com.velocitypowered.api.util.UuidUtils.generateOfflinePlayerUuid` del jar real
  de Velocity (bytecode + bootstrap method constant `"OfflinePlayer:\u0001"` vía `javap -v`), y el
  resultado se verificó con dos implementaciones independientes (PowerShell/.NET `MD5` +
  Node.js `crypto`, con formateo manual del string RFC4122 big-endian en ambas, evitando el reordenado de
  bytes mixed-endian del constructor `System.Guid(byte[])` de .NET). Ambas coincidieron exactamente.
- **`enforce-secure-profile` (backends) y `force-key-authentication` (Velocity)**: ambos requieren una
  clave pública firmada por Mojang, imposible para un cliente sin cuenta premium — se identificó y
  desactivó explícitamente en ambos lados (no solo en Velocity) antes de que causara un rechazo de
  conexión real, en vez de descubrirlo por ensayo y error.
- **Auditoría de datos de mods nuevos**: se revisaron los archivos creados por el modset nuevo del usuario
  fuera de `world/` (`config/betterendisland-fabric-1_20.toml`, `c2me.toml`,
  `cardinal-components-api.properties`, `cupboard.json`, `dragonfight.json`, `eldritch_mobs.json5`,
  `ferritecore.mixin.properties`, `lithium.properties`, `majruszlibrary.json`,
  `majruszsdifficulty.json`) — todos son configuración global del mod (INFRASTRUCTURE_SCOPED), ninguno
  contiene estado por-jugador. No se encontró evidencia de que algún mod nuevo persista estado de jugador
  fuera de `world/`, lo cual apoya (pero no reemplaza) la hipótesis de que el borrado de `world/` en cada
  recycle ya es suficiente para el reset de estado de run — pendiente de confirmar con el test formal de
  estado marcado (ver README "Estado actual" y ARCHITECTURE "Estado por fase" para el checklist pendiente).

## Hallazgos de FASE 9 (READY_FOR_USER_VALIDATION - pendiente confirmación visual del usuario)

- **hardcore-hud 0.1.0** (mod Fabric nuevo): `fabric-mods/hardcore-hud/`. Mismo toolchain verificado en
  FASE 7 (Fabric Loom 1.17.20, Java 21 solo como build-tool, `loom.officialMojangMappings()`). Error de
  scaffolding real cometido y corregido en el momento: al copiar la estructura de proyecto de
  `hardcore-death-signal` como plantilla, `settings.gradle` conservó `rootProject.name =
  "hardcore-death-signal"` sin renombrar — el primer build produjo `hardcore-death-signal-0.1.0.jar` en
  `fabric-mods/hardcore-hud/build/libs/` en vez de `hardcore-hud-0.1.0.jar`. Detectado inmediatamente al
  listar el directorio de build antes de copiar el jar (no asumido), corregido y reconstruido.
- **API vanilla de scoreboard verificada con `javap`** contra el jar remapeado real (no asumida):
  `Scoreboard.addObjective(String, ObjectiveCriteria, Component, ObjectiveCriteria.RenderType)`,
  `ObjectiveCriteria.HEALTH`, `ObjectiveCriteria.RenderType.HEARTS`, `Scoreboard.DISPLAY_SLOT_LIST`
  (constante pública, no un índice `0` hardcodeado), `Scoreboard.setDisplayObjective(int, Objective)`.
  Confirmado también en vivo por RCON antes de escribir código: `/scoreboard objectives add ... health`,
  `/scoreboard objectives modify ... rendertype hearts` (nota: este comando específico no devuelve texto
  de confirmación por RCON a diferencia de `displayname`, confirmado contrastando con un valor de
  rendertype deliberadamente inválido que sí devuelve error - no es un fallo silencioso) y
  `/scoreboard objectives setdisplay list ...` funcionan sin ningún mod en Minecraft 1.20.1 vanilla.
- **Convención de yaw de Minecraft verificada, no asumida**: para el cálculo de la brújula se confirmó
  contra la propia lógica de `Entity` que yaw=0° apunta a +Z (sur) y yaw aumenta en sentido horario visto
  desde arriba (yaw=90°→oeste, 180°→norte, 270°→este), dando `dirX=-sin(yaw)`, `dirZ=cos(yaw)`. La fórmula
  inversa usada (`targetYaw = atan2(-dx, dz)`) se verificó manualmente contra los 4 casos cardinales antes
  de implementarla.
- **Resource pack verificado end-to-end antes de configurar nada** (spec sección 7.1): URL de origen
  (`https://github.com/sgilbenavidez/minecraft-hardcore-assets`) resuelta a su descarga raw real vía la
  API de GitHub (`api.github.com/repos/.../contents/`), nunca la URL `blob/` (que devuelve HTML, no el
  ZIP). Descargado y verificado:
  ```text
  RESOURCE_PACK_URL    = https://raw.githubusercontent.com/sgilbenavidez/minecraft-hardcore-assets/main/HardcoreVisuals-1.20.1.zip
  RESOURCE_PACK_SHA1   = 3891902d19dbf33b877b6192af810219fd44f022  (coincide exacto con el .sha1 publicado)
  RESOURCE_PACK_SHA256 = bac922e8bfcf85ef48399b2c945a0a2113c7b5d56a2e7266537e074dfe3be166  (coincide exacto con el .sha256 publicado)
  ```
  El ZIP se abrió y confirmó: `pack.mcmeta` → `pack_format: 15` (verificado contra la tabla oficial de
  pack formats: 15 = exactamente 1.20-1.20.1, no una versión cercana), y contiene únicamente
  `assets/minecraft/textures/gui/icons.png` — el sprite sheet vanilla de corazones/hambre/armadura;
  inspeccionado visualmente y confirmado que reemplaza la fila de corazones normales por el patrón
  agrietado de corazones Hardcore, sin tocar el resto del sprite sheet.
- **Java `Properties` re-escapó automáticamente el `:` de la URL** en `server.properties`
  (`resource-pack=https\://raw.githubusercontent.com/...`) al reescribir el archivo en el siguiente
  arranque del backend — esto es el comportamiento estándar y documentado de
  `Properties.store()`/`Properties.load()` (round-trips correctamente, `\:` se lee de vuelta como `:`), no
  una corrupción; confirmado que el archivo sigue siendo válido tras el reinicio.
- **Decisión `hardcore=true` vs. resource pack, con evidencia (spec sección 7.4)**: en un servidor
  dedicado (no singleplayer), `hardcore=true` únicamente fuerza `GameType.SPECTATOR` al jugador que murió
  — no detiene el servidor, no borra el mundo, los demás jugadores siguen jugando con normalidad. Este es
  el modelo de "muerte individual" que el proyecto reemplaza deliberadamente por "una muerte = toda la run
  compartida se pierde" desde FASE 7. Activar `hardcore=true` no aportaría ninguna capacidad ausente y sí
  una segunda autoridad de muerte parcialmente solapada con la contención de FASE 8. Se mantiene
  `hardcore=false` con el resource pack visual — ver README.md para la matriz de comparación completa.
  `require-resource-pack=false` (no `true`): se prefirió no arriesgar desconectar a un jugador por rechazar
  el prompt del pack durante las pruebas iniciales; ajustable por el usuario una vez conforme.
- **hardcore-hud 0.1.0 → 0.2.0**: la primera versión mostraba la brújula como texto en el action bar
  (sin mod de cliente). El usuario, tras probarla, pidió en su lugar un punto visual moviéndose sobre la
  barra de XP con un color por jugador — se confirmó explícitamente con el usuario que esto requiere un
  mod de cliente (dibujar sobre un elemento del HUD no es posible solo con servidor) antes de construirlo.
  `fabric.mod.json` pasó de `"environment": "server"` a `"*"`, con un nuevo entrypoint `"client"`.
  **APIs nuevas verificadas con `javap` contra el jar real, no asumidas**:
  - `Gui.renderExperienceBar(GuiGraphics, int)`: geometría de la barra de XP obtenida directamente del
    bytecode (no de documentación de terceros) — 182px de ancho, `x = screenWidth/2 - 91` (calculado en el
    llamador, offset local `bipush 91`), `y = screenHeight - 32 + 3 = screenHeight - 29`.
  - `ServerPlayNetworking.send(ServerPlayer, ResourceLocation, FriendlyByteBuf)` /
    `ClientPlayNetworking.registerGlobalReceiver(ResourceLocation, PlayChannelHandler)` (paquete
    `net.fabricmc.fabric.api.(client.)networking.v1`, módulo `fabric-networking-api-v1`, ya incluido en el
    umbrella `fabric-api` — sin dependencia nueva).
  - `HudRenderCallback.EVENT` (`net.fabricmc.fabric.api.client.rendering.v1`, módulo `fabric-rendering-v1`,
    igual sin dependencia nueva) — dispara después del HUD vanilla, permitiendo dibujar encima sin pisar el
    render de la barra de XP en sí.
  - `GuiGraphics.fill(int,int,int,int,int)`, `GuiGraphics.guiWidth()/guiHeight()`,
    `ResourceLocation(String,String)`, `FriendlyByteBuf(ByteBuf)` con `writeInt`/`readInt`/`writeFloat`/
    `readFloat`.
- **Error de scaffolding real cometido y corregido en el momento**: al copiar la plantilla de proyecto de
  `hardcore-death-signal` para crear `hardcore-hud`, `settings.gradle` conservó
  `rootProject.name = "hardcore-death-signal"` sin renombrar — el primer build produjo
  `hardcore-death-signal-0.1.0.jar` dentro de `fabric-mods/hardcore-hud/build/libs/` en vez de
  `hardcore-hud-0.1.0.jar`. Detectado al listar el directorio antes de copiar el jar (no asumido el
  nombre), corregido de inmediato.
- **Distribución del mod de cliente**: `client/hardcore-hud/hardcore-hud-0.2.0.jar` — mismo jar que
  `server-a/mods/`/`server-b/mods/` (un solo artefacto, sin build separado para cliente/servidor).
  Entregado al usuario vía archivo adjunto; requiere Fabric Loader 0.19.5+ y Fabric API
  0.92.12+1.20.1 del lado cliente, exactamente las mismas versiones LOCKED que el servidor.
- **hardcore-hud 0.2.0 → 0.3.0**: el usuario reportó un error en su cliente tras instalar el mod de
  cliente de v0.2.0 y pidió eliminar el mod del servidor por completo, y luego rediseñar la brújula como
  flechas de colores (una por compañero, sin nombre) para varios jugadores conectados a la vez, sin volver
  a requerir ningún mod de cliente. `fabric.mod.json` volvió a `"environment": "server"` con un único
  entrypoint `"main"`; se eliminaron `HardcoreHudClientMod.java` y `HardcoreHudNet.java` (ya no hay canal
  de red). `net.minecraft.ChatFormatting` (colores de texto vanilla, no Adventure) verificado con `javap`
  contra el jar real para el índice de color por UUID.
- **player-locator-plus 2.2.0**: mod de terceros añadido manualmente por el usuario dos veces (una con
  nombre correcto en `server-a/mods`, otra renombrado a `server.jar` en `server-b/mods` — mismo jar,
  confirmado leyendo su `fabric.mod.json` interno antes de actuar). Ambas veces impidió el arranque
  completo de A y B (`HARD_DEP_NO_CANDIDATE player-locator-plus 2.2.0 {depends cloth-config @
  [>=11.1.136]}` — la versión instalada es `cloth-config-11.1.106`). Puesto en cuarentena
  (`runtime/modset-quarantine/`, no borrado) ambas veces para restaurar servicio; pendiente de decisión
  del usuario (actualizar `cloth-config`, buscar una build compatible, o descartarlo).
- **hardcore-hud 0.3.0 → 0.4.0**: añadido contador de muertes/runs a pedido del usuario. Verificado con
  `javap` contra el jar real: `ObjectiveCriteria.DUMMY`, `Scoreboard.DISPLAY_SLOT_SIDEBAR`,
  `Objective.setDisplayName(Component)`, `Scoreboard.getOrCreatePlayerScore(String, Objective)`,
  `Score.setScore(int)`, `Scoreboard.resetPlayerScore(String, Objective)`,
  `Scoreboard.getPlayerScores(Objective)`. Vanilla no ofrece ningún display slot en la esquina
  superior-izquierda vía comandos — se usó `sidebar` (superior-derecha) como la aproximación discreta más
  cercana sin mod de cliente, documentado explícitamente como una diferencia de posición, no asumido como
  equivalente. Lee `runId` de `state.json` de `HardcoreCoordinator` (un proceso Velocity distinto) por
  regex mínima, en modo exclusivamente lectura — mismo patrón de desacople por archivo ya usado entre
  `HardcoreDeathSignal` y `HardcoreCoordinator` para el spool de muertes, nunca usado para lógica de
  control (cumple la restricción de la sección 4 del spec de no tocar el ciclo de vida real de `runId`).
- **hardcore-hud 0.4.0 → 0.5.0 (2026-09-22)**: se eliminó por completo la brújula hacia el compañero a
  pedido explícito del usuario, que ya tiene otro mod que cubre esa función. Se quitaron de
  `HardcoreHudMod.java`: `updateCompassFor`, `sendColoredArrows`, `sendOtherDimensionIndicator`,
  `colorIndexFor`, `normalizeDegrees`, `arrowFor`, `friendlyDimensionName`, y las constantes
  `COMPASS_INTERVAL_TICKS`, `ARROWS`, `PALETTE`, `compassTickCounter`. `onEndTick` ahora solo dispara el
  contador de muertes; TAB hearts y el contador de muertes quedan sin cambios funcionales.
