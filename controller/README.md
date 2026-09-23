# controller

Directorio reservado para el orquestador futuro de A/B.

No implementado todavía (FASE 0). Su responsabilidad futura será:

- gestionar los estados `ACTIVE` / `STANDBY` / `RECYCLING` / `STARTING` / `READY` / `FAILED`
- health checks de Server A y Server B
- `STOP` → `DELETE WORLD` → nueva seed → `START` → `READY` del backend reciclado
- coordinar el switch de jugadores entre A y B a través de Velocity

El lenguaje/tecnología del controller (Java, Python, Node, PowerShell) no se ha decidido todavía; se elegirá
cuando exista una razón técnica clara, no antes. Ver `ARCHITECTURE.md` para el lifecycle completo.
