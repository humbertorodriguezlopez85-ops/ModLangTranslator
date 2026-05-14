# Mod Lang Translator (Minecraft)

Aplicacion Java con interfaz grafica para traducir automaticamente archivos de idioma desde `en_us.json` dentro de un `.jar` de mod, sin descomprimir manualmente.

## Que hace

- Abre un `.jar` de mod directamente.
- Busca entradas `*/lang/<idioma_origen>*.json`.
- Traduce solo valores de texto (no claves).
- Mantiene elementos que no deben traducirse:
  - Codigos de formato como `§a`, `§6`, etc.
  - Placeholders como `%1$s`, `%s`, `%d`.
  - Identificadores con `.` o `_`.
  - Contenido entre parentesis (para evitar romper variables o tiempos).
- Crea un nuevo `.jar` con archivos `<idioma_destino>*.json` agregados/reemplazados.

## Requisitos

- Java 17+
- Maven 3.9+
- API de traduccion compatible con LibreTranslate (`/translate`)

## Ejecutar

```bash
mvn compile exec:java
```

Si no tienes Maven instalado (pero si tienes Java):

```powershell
./run.ps1
```

## Crear ejecutable (.exe)

```powershell
./build_exe.ps1
```

El ejecutable se genera en:

`dist/ModLangTranslator/ModLangTranslator.exe`

## Uso rapido

1. Pulsa `Browse...` y elige el `.jar` del mod.
2. Ajusta endpoint de traduccion (por defecto `https://libretranslate.com/translate`).
3. (Opcional) agrega API key.
4. Selecciona idioma origen y destino (por defecto `en_us` -> `es_es`).
5. Pulsa `Translate and Build JAR`.
6. Se genera `<nombre_mod>_<idioma_destino>.jar` en la misma carpeta del mod.
7. Si la API responde con errores (403/429) y quieres detener el proceso, pulsa `Stop`.

## Nota

Algunos endpoints publicos de LibreTranslate tienen limite de uso o pueden estar caidos. Si falla, prueba otro endpoint compatible o uno privado.

## Logs de errores

La app guarda log con fecha/hora en:

`logs/mod-lang-translator.log`

Incluye mensajes de proceso y stacktrace cuando ocurre una excepcion.
