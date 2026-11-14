# SF2 Extractor

App Android para ver, filtrar, combinar, separar y comprimir archivos SoundFont (.sf2).

- Abre uno o varios SF2 y lista todos sus instrumentos (presets), con tamaño y cuántas veces se repite cada nombre.
- Marca los que quieres conservar (filtros por categoría: baterías, pianos, bajos…).
- Exporta los marcados en un solo SF2 (combinando varios archivos) o en un SF2 por instrumento.
- Opciones de compresión: quitar efectos, quitar moduladores, estéreo a mono, 16 bits, bajar la frecuencia de muestreo.

El APK se compila con GitHub Actions y se publica en *Releases*.

## Versión para Windows (.exe)

El módulo `desktop` es la misma app para Windows, hecha con Compose for Desktop y usando el mismo motor SF2 que la versión Android.

- Se abre en **pantalla completa** y siempre en **horizontal**.
- **Esc** sale de pantalla completa (queda una ventana horizontal que no se puede estirar a vertical); **F11** vuelve a pantalla completa.
- Los archivos se abren y guardan con los diálogos normales de Windows.

GitHub Actions (`Build Windows EXE`) genera el instalador `SF2-Extractor-Setup.exe` y lo publica en *Releases* con cada push a `main`. No pide permisos de administrador y crea accesos directos en el escritorio y en el menú Inicio.

Para compilarlo en una PC con Windows y JDK 17: `gradle :desktop:packageExe` (o `:desktop:run` para probarlo sin empaquetar).
