# M3U Live TV — Android

Primera versión de un reproductor Android centrado únicamente en televisión en directo mediante listas M3U/M3U8.

## Funciones incluidas

- Añadir una lista mediante URL HTTP/HTTPS.
- Importar una lista M3U/M3U8 desde el almacenamiento del dispositivo.
- Guardar la última lista en el almacenamiento privado de la aplicación.
- Actualizar listas online desde la propia aplicación.
- Leer nombre, identificador, logotipo y categoría (`tvg-name`, `tvg-id`, `tvg-logo`, `group-title`).
- Mostrar canales por categorías y buscar por nombre, grupo o identificador.
- Guardar favoritos localmente.
- Reproducir a pantalla completa con controles, cambio de orientación y gestión básica de errores.
- Enviar cabeceras habituales de listas IPTV (`User-Agent`, `Referer`, `Origin`, `Cookie`) cuando se indiquen en la lista.
- Navegación con controles táctiles y foco del mando de Android TV.
- Interfaz en español, sin publicidad ni servicios de cuenta.

## Formatos / fuentes

El reproductor utiliza AndroidX Media3 / ExoPlayer 1.11.1, con soporte HLS y RTSP, además de los formatos que procesan los extractores incluidos en Media3. La compatibilidad final depende del códec y del formato de cada emisión y del dispositivo Android.

El analizador reconoce `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT` y `#EXTHTTP`. También extrae algunas cabeceras de URLs que utilicen el formato habitual `url|User-Agent=...&Referer=...`.

## Requisitos

- Android 13 o posterior (API 33+).
- Android Studio actualizado.
- JDK 17.
- Android SDK Platform 36.
- Conexión a Internet durante la primera sincronización para descargar Gradle, Kotlin y las dependencias Media3.

## Compilar en Windows

1. Descomprime la carpeta del proyecto.
2. Abre la carpeta `M3ULiveAndroid` desde Android Studio.
3. Instala Android SDK Platform 36 si Android Studio lo solicita.
4. Configura el JDK de Gradle en 17 o superior compatible.
5. Sincroniza el proyecto y espera a que se descarguen las dependencias.
6. Selecciona **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
7. El APK de depuración se generará en `app\build\outputs\apk\debug\app-debug.apk`.

El proyecto fija Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.4.21 y Media3 1.11.1. También se incluye `compilar-apk.bat`: en Windows descarga Gradle si hace falta y ejecuta la compilación. Es necesario tener Android SDK Platform 36 instalado y sus licencias aceptadas.

## Alcance de esta versión

Esta entrega implementa una única lista activa cada vez. Se puede sustituir por otra URL o archivo. No incluye películas, series, EPG/XMLTV, Xtream Codes, grabaciones, Timeshift, control parental ni gestión simultánea de varias listas. Esas partes no se muestran ni se cargan en la interfaz.

No se incluye un APK precompilado en esta entrega: el entorno de generación no dispone de Android SDK ni de Gradle, por lo que no he podido ejecutar la compilación ni probarla en un dispositivo real. El contenido entregado es el proyecto fuente preparado para compilar en Android Studio; no debe considerarse una compilación verificada hasta completar ese paso.

## Seguridad y privacidad

La aplicación almacena la lista importada y los favoritos en el espacio privado de la app. Se permite tráfico HTTP sin cifrar para no excluir listas antiguas que todavía lo utilizan; siempre que sea posible, utiliza URLs HTTPS y listas de proveedores de confianza. Las URLs de las listas pueden contener credenciales, por lo que no conviene compartirlas.

La aplicación es un reproductor: el usuario debe utilizar únicamente listas y emisiones cuyo acceso esté autorizado.
