# MCP User Authentication & Chat Client

## 📖 ¿Qué es este proyecto?

Este proyecto es un cliente **Model Context Protocol (MCP)** desarrollado sobre **Quarkus** que permite conectarse dinámicamente a servidores MCP y conversar con la IA de Google Gemini 2.0 Flash. La herramienta interactúa con las capabilities (herramientas) reportadas por los servidores MCP proporcionando respuestas ricas a los usuarios.

Novedades de la versión actual:
* **Sistema de Usuarios**: Los usuarios pueden registrarse y conectar su propia Gemini API Key.
* **Seguridad Avanzada**: Las API Keys de cada usuario se guardan cifradas en base de datos usando **AES-256-GCM**.
* **Autenticación JWT**: Los endpoints del chat están protegidos por tokens JWT sin necesidad de usar un proveedor de identidad de terceros como Keycloak.

## ⚙️ ¿Cómo funciona?

1. **Registro:** El usuario introduce su nombre, contraseña y API Key de Gemini. El backend hashea la contraseña con BCrypt y cifra la API key en memoria con `APP_MASTER_KEY` antes de guardarla en PostgreSQL.
2. **Login:** Al comprobar credenciales, la API expide un `JWT` (caducidad 8h) autorizado que el frontend almacena en el `localStorage`.
3. **Chat Seguro:** Todas las llamadas al chat adjuntan el `Authorization: Bearer <token>`. El servidor identifica al creador subyacente del token (`userId`), va a la base de datos (por caché), extrae y descifra en demanda la API key, y la utiliza contra los servidores de Google.
4. **Invalidación Simple:** El usuario puede actualizar su API Key por medio de la interfaz gráfica y Quarkus descarta automáticamente su llave cacheada de memoria.

---

## 🚀 Puesta en Funcionamiento

### 1. Requisitos Previos
* Tener **Docker** y **Docker Compose** instalados (para PostgreSQL y empaquetado final).
* Tener **OpenSSL** instalado (ejecución por CLI para la generación de llaves de asimetría RSA).
* Java 17 o superior.

### 2. Generar el Par de Claves RSA (para JWT)
El sistema utiliza un sistema asimétrico de llaves para firmar los accesos de usuario. Estas llaves tienen que depositarse dentro de los recursos de Quarkus:

```shell script
cd src/main/resources
openssl genrsa -out rsaPrivateKey.pem 2048
openssl rsa -pubout -in rsaPrivateKey.pem -out publicKey.pem
cd ../../../
```

### 3. Configurar las Variables de Entorno

Toma de ejemplo el `.env.example` provisto para generar el archivo de configuración base:

```shell script
cp .env.example .env
```
⚠️ **MUY IMPORTANTE**: En tu archivo `.env`, localiza la propiedad `APP_MASTER_KEY`. Para que la encripción AES-256-GCM funcione correctamente, el string de la key debe tener **EXACTAMENTE 32 caracteres**. Por ejemplo:
`APP_MASTER_KEY=MySecurePasswordForAesEncrypt123`

### 4. Iniciar PostgreSQL

El proyecto contiene un esquema y migraciones generados via Flyway. Inicia la base de datos directamente con:

```shell script
docker compose up postgres -d
```

### 5. Compilar y Ejecutar Quarkus

Una vez la base de datos esté lista, arranca la aplicación de en modo "Dev" para compilar todo y ejecutar en tiempo real:
*(Recuerda tener correctamente configurado APP_MASTER_KEY como variable de entorno si la ejecutas manual, o exportala en la terminal previamente)*

**Opción A (Quarkus Dev):**
```shell script
# Puedes ejecutar el comando inyectando la variable manualmente (útil para cmd/powershell o Bash):
./mvnw quarkus:dev
```

**Opción B (Docker Compose Completo):**
```shell script
docker compose up --build -d
```
*(Si levantas solo la base de datos, debes ejecutar Quarkus a mano exponiendo la llave maestra y URL de BBDD en las variables de entorno).*

### 6. Uso desde el Navegador
Abre [http://localhost:8081](http://localhost:8081).
* Se presentará un formulario de **Ingreso** / **Registro** central.
* Regístrate y accede con tu llave válida de Gemini.
* Puedes cambiar esta llave pulsando "⚙️ API Key" en la cabecera del chat la próxima vez que inicies sesión.
