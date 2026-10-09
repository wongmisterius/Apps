# Tomodachi Life: Living the Dream en NX on Orbis

Continuación del port con un objetivo concreto: que *Tomodachi Life: Living the Dream* (Switch,
2026) arranque y se pueda jugar en una PS4 Pro. Se prueba en una PS4 Pro con **FW 11.00 + GoldHEN**
(el port original solo se probó en 12.02).

## Qué cambió respecto de v0.1.0 ("tl1")

| Cambio | Por qué |
|---|---|
| **Colores R↔B corregidos** (parche `0029`) | El frame de presentación se creaba con el formato del swapchain (`R8G8B8A8`, el único que ofrece el WSI de la PS4) pero se dibujaba a través de una vista `B8G8R8A8` fija. Cada píxel quedaba con rojo y azul intercambiados y el blit al swapchain lo mostraba tal cual. Ahora la vista sigue el orden de canales del swapchain. |
| **Teclado de la PS4** (`frontend/ps4_keyboard.*`) | El teclado por defecto de Eden escribe "Eden" en todo campo de texto. Tomodachi Life pide nombres (Miis, isla); ahora se abre el teclado del sistema (`sceImeDialog`). Mientras está abierto el juego no recibe el mando. |
| **Touchpad = pantalla táctil** | Tocar el touchpad del DS4 toca la pantalla de la Switch en esa posición (absoluta). Hacer clic en el touchpad sigue siendo el botón −, y mientras está apretado no se reporta toque. `touchpad=off` en `settings.txt` lo desactiva. |
| `docked=on/off` en `settings.txt` | Modo TV (1080p, más GPU) o portátil (720p, por defecto). |
| Build en Linux | Scripts sin rutas de Windows; `scripts/build-libcxx.sh`, `scripts/build-deps.sh`. Ver `BUILDING.md`. |

Eden base: `5f142c79` (9 de septiembre de 2026). Su firmware HLE ya es **22.5.0**, que es lo que
piden los reportes para este juego; no hizo falta pasar a otro Eden.

## Qué necesitás (de tu propia Switch)

- `prod.keys` + `title.keys` **de una Switch con firmware 22.x o más nuevo** (si las claves son
  más viejas que el juego, no descifra: `boot.log` / `eden_log.txt` lo dicen).
- El firmware 22.x volcado (`.nca`) en `/data/edenps4/firmware/`.
- El juego (`.nsp`/`.xci`) en `/data/edenps4/roms/` y, si lo tenés, **la última actualización** en
  `/data/edenps4/updates/` (varios cierres reportados en PC se arreglan con el update).

Instalación: igual que [INSTALL.md](INSTALL.md) (FTP en modo binario, Package Installer de GoldHEN).

## Prueba 1: qué mirar y qué mandar

1. Borrá la caché vieja de shaders si venías de v0.1.0 (no es obligatorio).
2. Abrí *NX on Orbis*, elegí Tomodachi Life y anotá hasta dónde llega:
   - ¿se ve el logo de Nintendo / pantalla de título? ¿los **colores** son correctos?
   - ¿aparece el **teclado de la PS4** cuando pide un nombre? ¿el nombre llega al juego?
   - ¿funciona tocar con el touchpad?
   - fps aproximados (el `boot.log` los escribe cada 10 s en las líneas `status:`).
3. Pasá por FTP (modo binario) estos archivos **antes de volver a abrir la app**:
   - `/data/edenps4/boot.log`
   - `/data/edenps4/mesa.log`
   - `/data/edenps4/log/eden_log.txt`
4. Si se cuelga o se cierra: foto de la pantalla y los mismos tres logs. Las líneas
   `!! CRASH` / `eboot+0x...` se traducen con el ELF de *ese* paquete (`elf/`).

Líneas nuevas en `boot.log`:

| Línea | Significa |
|---|---|
| `PS4 build: tl1-...` | es este build |
| `ime: load module ..., common dialog ...` | se cargó el teclado (valores negativos = falló) |
| `ime: closed (ok), N characters` | se escribió un texto |
| `PS4 swapchain format 37 -> presentation view format 37` (en `eden_log.txt`) | la corrección de color está activa (37 = R8G8B8A8_UNORM) |

## Riesgos conocidos

- **Velocidad**: el límite es la CPU Jaguar (ver `STATUS.md`). Tomodachi Life es menos exigente
  que Mario Kart 8, pero no hay medición todavía.
- **Memoria**: el proceso tiene ~4,4 GiB; si el juego se cuelga cargando, mirá `direct free` en las
  líneas `status:` de `boot.log`.
- **FW 11.00**: el armado del paquete (firma, `sce_module/`) se validó en 12.02. Si no abre o da
  poca memoria (`boot.log` lo reporta al inicio), es lo primero a revisar.
