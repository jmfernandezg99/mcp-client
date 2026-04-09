# MCP Platform

Proyecto Quarkus dividido en dos modulos:

- `control-plane`: gestion multiusuario de cuentas, API keys, servidores MCP por URL y arranque local del runtime del usuario.
- `workspace-runtime`: runtime de chat minimo que usa `quarkus-langchain4j-mcp` de forma declarativa.

## Flujo

1. El usuario se registra en `control-plane` con su Gemini API key.
2. Guarda uno o varios servidores MCP `streamable-http` por `nombre + URL`.
3. Pulsa `Aplicar`.
4. `control-plane` genera la configuracion declarativa del `workspace-runtime` para ese usuario.
5. Pulsa `Arrancar runtime`.
6. `control-plane` levanta el `workspace-runtime` local con la API key del usuario ya inyectada.
7. El chat del `control-plane` hace proxy a ese runtime aislado.

## Estructura

- `control-plane/src/main/java/org/acme/mcp`
  - auth, configuracion MCP, workspace, launcher local y proxy de chat.
- `workspace-runtime/src/main/java/org/acme/runtime`
  - `WorkspaceAssistant`: AI service declarativo con `@McpToolBox`.
  - `ChatResource`: endpoint de chat del runtime.

## Por que esta arquitectura

Se aprovecha Quarkus y `quarkus-langchain4j-mcp` donde mejor encajan:

- el runtime de chat arranca con clientes MCP declarativos,
- cada usuario mantiene su propia configuracion y su propia API key,
- el control plane solo orquesta configuracion y lifecycle local,
- no se reimplementa el protocolo MCP ni se hace tool-calling artesanal.

## Arranque local

### Control plane

```powershell
.\mvnw.cmd -pl control-plane quarkus:dev
```

En desarrollo, `control-plane` usa H2 embebida y no necesita Docker. Para produccion o para usar una base externa, define `DB_URL`, `DB_USER` y `DB_PASS` en `control-plane/.env`.

### Workspace runtime

No hace falta pasar la API key a mano. El propio `control-plane` puede arrancarlo desde la UI con el boton `Arrancar runtime`.

Si quieres arrancarlo manualmente, usa el comando generado en la vista previa del workspace.

## Tests

```powershell
.\mvnw.cmd test
```
